package com.agentflow.engine;

import com.agentflow.llm.LlmClient;
import com.agentflow.model.PlanStep;
import com.agentflow.model.ToolCall;
import com.agentflow.tool.GitLabTool;
import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用 Agent 编排引擎：任意自然语言指令 → 意图分析 → 动态规划 → 逐步执行 → 汇总。
 * 有 LLM 时规划/推理/生成走 LLM（write 步流式输出）；无 LLM 时走启发式拆解 + 真实数据 API + 明确标注的模拟内容。
 * 每次运行的全部 SSE 事件同步落 SQLite（RunStore），支持前端历史回放。
 */
@Service
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);
    private static final long EMITTER_TIMEOUT_MS = 180_000L;
    private static final int MAX_REASON_LINES = 10;
    private static final int MAX_HISTORY_TURNS = 5;
    private static final int STREAM_FLUSH_CHARS = 16;
    private static final int MAX_SESSIONS = 500;
    /** confirm 模式等待用户确认计划的最长时间 */
    private static final long CONFIRM_TIMEOUT_MIN = 10;
    /** ReAct 自主循环的步数上限 */
    private static final int MAX_REACT_STEPS = 8;
    /** 混合模式：计划受阻转入 ReAct 后的额外步数预算（比纯 react 收紧，避免失控） */
    private static final int MAX_HYBRID_REACT_STEPS = 4;
    /** 同一并行分组内的最大并发数 */
    private static final int MAX_PARALLEL = 3;

    private static final String PLAN_SYSTEM_TEMPLATE =
            "你是 AgentFlow 的意图分析与任务规划器。请分析用户指令并拆解成 2~5 个有序、可执行的子任务，输出一个 JSON 对象：" +
            "{\"summary\": \"一句话概括用户想要什么（30 字以内）\", \"entities\": [\"关键实体，如 城市:北京、股票:贵州茅台、日期:明天\"], " +
            "\"steps\": [{\"kind\": \"tool|think|write\", \"tag\": \"简短类型标签\", \"title\": \"一句话子任务描述\", " +
            "\"group\": 0, \"tool\": {\"name\": \"工具名\", \"args\": {参数对象}}}]}\n" +
            "规则：\n" +
            "- kind：tool=调用工具取数，think=分析推理，write=生成最终交付内容；tool 字段仅 kind=tool 时给出，否则为 null\n" +
            "- 最后一步一般是 write；需要数据支撑时先安排 tool 步，再 think，最后 write\n" +
            "- args 必须按工具参数说明填写结构化 JSON 对象\n" +
            "- 相互独立、可并行执行的多个 tool 步使用相同 group（正整数，从 1 开始）；有依赖或不需要并行时 group 为 0\n" +
            "- 指令引用对话历史中的内容时，规划应基于历史产出继续加工而不是重新取数\n" +
            "可用工具：\n%s" +
            "只输出 JSON，不要输出任何多余文字或代码块。";

    private static final String REACT_SYSTEM_TEMPLATE =
            "你是 AgentFlow 的执行决策模块（ReAct）。根据任务目标与已有执行结果决定下一步动作，只输出一个 JSON 对象：\n" +
            "{\"action\": \"tool|write|finish\", \"tool\": {\"name\": \"工具名\", \"args\": {}}, \"note\": \"一句话说明这一步做什么\"}\n" +
            "规则：\n" +
            "- 需要外部数据才能继续时选 tool，args 按工具参数说明填写\n" +
            "- 数据已足够支撑交付时选 write（生成最终内容，note 说明交付物方向）\n" +
            "- 目标已达成、无需再生成时选 finish\n" +
            "- 禁止与已有动作完全重复的工具调用\n" +
            "可用工具：\n%s";

    private static final String THINK_SYSTEM =
            "你是 AgentFlow 智能体的分析模块。针对当前任务直接输出分析内容本身（不是'如何回答'的内心独白），" +
            "共 5~8 行，每行独占一行且以要点名开头，严格使用以下要点名：\n" +
            "目标拆解：用户要什么，交付物是什么形式\n" +
            "关键要素：涉及的对象、条件、约束\n" +
            "数据解读：一条数据一行，直接引用具体数字与事实\n" +
            "思路选择：生成/执行策略及理由\n" +
            "结论：一句话给出产出方向\n" +
            "禁止出现'用户需要''格式要求''好的''接下来'等对回答方式的描述，禁止空泛套话。";

    private static final String WRITE_SYSTEM =
            "你是 AgentFlow 智能体中的内容生成模块，负责生成最终交付物。请直接输出成品内容（无需任何解释），" +
            "并严格遵守任务要求。";

    private static final String FINAL_SYSTEM =
            "你是 AgentFlow 智能体，负责把各子任务的执行结果汇总成最终交付物。" +
            "请按以下格式输出：第一行是一句话总结（30 字以内），空一行后输出完整成品。";

    private static final String EXTRACT_SYSTEM =
            "你是 AgentFlow 的记忆提取器。从本轮任务中提取值得跨会话长期记住的用户偏好与既定事实" +
            "（如常用环境/集群、默认连接、署名与称呼、固定习惯、明确的服务↔项目对应关系），" +
            "不要记录一次性的任务数据或查询结果。输出一个 JSON 对象：" +
            "{\"add\": [\"新记忆，每条一句话、不超过 80 字\"], \"remove\": [\"被本轮明确否定或已过时的旧记忆原文\"]}\n" +
            "规则：没有值得记的就输出空数组；remove 必须与现有记忆原文完全一致；add 最多 3 条。只输出 JSON。";

    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final RunStore runStore;
    private final MemoryStore memoryStore;
    private final String reportDept;
    private final String agentMode;
    private final boolean memoryEnabled;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, RunSession> sessions = new ConcurrentHashMap<>();

    public AgentEngine(ToolRegistry toolRegistry, LlmClient llmClient, RunStore runStore, MemoryStore memoryStore,
                       @Value("${agentflow.report.department:中台研发部}") String reportDept,
                       @Value("${agentflow.agent.mode:plan}") String agentMode,
                       @Value("${agentflow.memory.enabled:true}") boolean memoryEnabled) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.runStore = runStore;
        this.memoryStore = memoryStore;
        this.reportDept = reportDept == null || reportDept.isBlank() ? "中台研发部" : reportDept.trim();
        this.agentMode = agentMode == null ? "plan" : agentMode.trim().toLowerCase();
        this.memoryEnabled = memoryEnabled;
    }

    /** ReAct 自主模式：配置开启且 LLM 可用时生效，失败自动回退线性规划 */
    private boolean reactEnabled() {
        return "react".equals(agentMode) && llmClient.isEnabled();
    }

    /** 混合模式：先按计划执行，工具步受阻（note 型降级）时放弃剩余计划、转 ReAct 自主决策 */
    private boolean hybridEnabled() {
        return "hybrid".equals(agentMode) && llmClient.isEnabled();
    }

    /** 提交任务：立即后台执行（不依赖前端订阅），事件全部落库，随时可 attach 查看 */
    public String start(String command, List<Map<String, String>> history, String mode) {
        return start(command, history, mode, null);
    }

    /** 提交任务（带对话归属 sessionId，历史列表按对话分组用） */
    public String start(String command, List<Map<String, String>> history, String mode, String sessionId) {
        String taskId = UUID.randomUUID().toString();
        long runId = runStore.createRun(taskId, command, sessionId);
        RunSession session = new RunSession(taskId, runId, command, sanitizeHistory(history),
                "confirm".equalsIgnoreCase(mode));
        sessions.put(taskId, session);
        session.markStarted();
        executor.submit(() -> orchestrate(session));
        return taskId;
    }

    /**
     * 无界面执行一个任务并等待完成（定时晨报等场景使用）。
     * 返回 {taskId, status, summary, output}；超过 timeoutMs 返回当前状态。
     */
    public Map<String, String> executeHeadless(String command, long timeoutMs) {
        String taskId = start(command, List.of(), "auto");
        RunSession session = sessions.get(taskId);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (session != null && !session.isFinished() && System.currentTimeMillis() < deadline) {
            sleep(500);
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        if (session == null) {
            out.put("status", "error");
            out.put("summary", "任务会话丢失");
            out.put("output", "");
            return out;
        }
        Map<String, Object> run = runStore.getRun(session.runId());
        out.put("status", run == null ? (session.isFinished() ? "done" : "running")
                : String.valueOf(run.getOrDefault("status", "running")));
        out.put("summary", run == null ? "" : String.valueOf(run.getOrDefault("summary", "")));
        out.put("output", run == null ? "" : String.valueOf(run.getOrDefault("output", "")));
        return out;
    }

    /** 只保留最近几轮且字段齐全的历史，避免 prompt 被无效内容撑爆 */
    private static List<Map<String, String>> sanitizeHistory(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> turns = new ArrayList<>();
        int from = Math.max(0, history.size() - MAX_HISTORY_TURNS);
        for (Map<String, String> h : history.subList(from, history.size())) {
            if (h == null) continue;
            String c = h.getOrDefault("command", "");
            String o = h.getOrDefault("output", "");
            if ((c == null || c.isBlank()) && (o == null || o.isBlank())) continue;
            Map<String, String> t = new LinkedHashMap<>();
            t.put("command", c == null ? "" : c);
            t.put("output", o == null ? "" : o);
            turns.add(t);
        }
        return turns;
    }

    /**
     * 订阅任务事件流（任务在提交时已开始后台执行）：补发 afterSeq 之后的存量事件并挂接实时推送。
     * afterSeq = 调用方已收到的最大事件序号，-1 表示从头补发。
     */
    public SseEmitter stream(String taskId, int afterSeq) {
        RunSession session = sessions.get(taskId);
        if (session == null) {
            return replayOnly(taskId, afterSeq);
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        wireEmitter(emitter, session);
        // 补发与挂接在 session 锁内原子完成，与事件推送互斥，保证不重不漏
        synchronized (session) {
            replayFromStore(emitter, session.runId(), afterSeq);
            if (session.isFinished()) {
                emitter.complete();
            } else {
                session.attach(emitter);
            }
        }
        return emitter;
    }

    /** 请求取消：引擎在步骤边界与流式生成回调处收尾 */
    public boolean cancel(String taskId) {
        RunSession session = sessions.get(taskId);
        if (session == null || session.isFinished()) {
            return false;
        }
        session.cancel();
        return true;
    }

    /** 前端确认/编辑后的计划回传；返回 false 表示任务不存在或已结束 */
    public boolean confirm(String taskId, List<Map<String, Object>> stepDefs) {
        RunSession session = sessions.get(taskId);
        if (session == null || session.isFinished()) {
            return false;
        }
        List<PlanStep> steps = new ArrayList<>();
        if (stepDefs != null) {
            for (Map<String, Object> d : stepDefs) {
                if (d == null) {
                    continue;
                }
                String kind = String.valueOf(d.getOrDefault("kind", "tool"));
                String tag = String.valueOf(d.getOrDefault("tag", kind));
                String title = String.valueOf(d.getOrDefault("title", "子任务"));
                int group = 0;
                try {
                    group = Integer.parseInt(String.valueOf(d.getOrDefault("group", "0")));
                } catch (Exception ignored) {
                }
                boolean skip = Boolean.parseBoolean(String.valueOf(d.getOrDefault("skip", "false")));
                ToolCall tool = null;
                if (d.get("tool") instanceof Map<?, ?> tm && tm.get("name") != null
                        && !String.valueOf(tm.get("name")).isBlank()) {
                    Map<String, Object> args = new LinkedHashMap<>();
                    if (tm.get("args") instanceof Map<?, ?> am) {
                        am.forEach((k, v) -> args.put(String.valueOf(k), v));
                    }
                    tool = new ToolCall(String.valueOf(tm.get("name")), args);
                }
                steps.add(new PlanStep(kind, tag, title, tool,
                        "think".equals(kind) ? List.of() : null, Math.max(0, group), skip));
            }
        }
        session.confirm(steps);
        return true;
    }

    private void wireEmitter(SseEmitter emitter, RunSession session) {
        emitter.onTimeout(() -> {
            session.detachEmitter(emitter);
            emitter.complete();
        });
        emitter.onError(e -> session.detachEmitter(emitter));
        emitter.onCompletion(() -> session.detachEmitter(emitter));
    }

    /** 从 SQLite 补发存量事件；订阅方已断开则停止补发 */
    private void replayFromStore(SseEmitter emitter, long runId, int afterSeq) {
        for (Map<String, Object> e : runStore.listEvents(runId, afterSeq)) {
            try {
                emitter.send(SseEmitter.event().name((String) e.get("event")).data(e.get("data")));
            } catch (Exception ex) {
                return;
            }
        }
    }

    /** 会话不在内存（服务重启或已淘汰）：按库内事件补发后直接收流 */
    private SseEmitter replayOnly(String taskId, int afterSeq) {
        Long runId = runStore.findRunIdByTaskId(taskId);
        if (runId == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        executor.submit(() -> {
            replayFromStore(emitter, runId, afterSeq);
            emitter.complete();
        });
        return emitter;
    }

    private void completeEmitter(RunSession session) {
        SseEmitter emitter = session.emitter();
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /** 会话数超限时淘汰已结束的，防止内存缓慢增长 */
    private void evictFinishedSessions() {
        if (sessions.size() <= MAX_SESSIONS) {
            return;
        }
        for (Map.Entry<String, RunSession> e : sessions.entrySet()) {
            if (sessions.size() <= MAX_SESSIONS * 3 / 4) {
                break;
            }
            RunSession s = e.getValue();
            if (s.isFinished()) {
                sessions.remove(e.getKey(), s);
            }
        }
    }

    private static void checkCancelled(RunSession session) {
        if (session.isCancelled()) {
            throw new CancelledException();
        }
    }

    /** 引擎内部取消信号：在步骤边界与流式生成回调处抛出 */
    private static final class CancelledException extends RuntimeException {
    }

    /* ================= 编排主流程 ================= */

    private void orchestrate(RunSession session) {
        RunRecorder recorder = new RunRecorder(session);
        String command = session.command();
        // 长期记忆块与对话历史块一起注入所有 LLM prompt（规划/推理/生成/汇总/ReAct 决策）
        String histBlock = memoryBlock() + historyBlock(session.history());
        // 提到 try 外：取消收尾时仍可引用已产出的部分结果
        List<PlanStep> steps = null;
        int total = 0;
        Map<String, String> toolResults = new LinkedHashMap<>();
        String writeOutput = null;
        // 记忆快速通道：显式「记住/忘记」不走规划，直接操作长期记忆并收尾（模拟模式同样可用）
        if (memoryEnabled) {
            MemoryCommand mc = parseMemoryCommand(command);
            if (mc != null) {
                handleMemoryCommand(recorder, session, mc);
                return;
            }
        }
        try {
            /* 阶段一+二：意图分析与任务规划（LLM 模式一次调用同时完成；confirm 模式推送计划等待放行；ReAct 跳过预规划） */
            recorder.send("phase", map("name", "understand", "state", "active"));
            recorder.send("status", map("text", "正在解析指令并规划任务…", "cls", "is-running"));
            Intent intent;
            if (reactEnabled()) {
                intent = heuristicIntent(command);
                recorder.send("intent", map("summary", intent.summary(), "entities", intent.entities()));
                recorder.send("phase", map("name", "understand", "state", "done"));
                recorder.send("phase", map("name", "plan", "state", "done"));
                recorder.send("status", map("text", "ReAct 自主模式 · 逐步决策执行…", "cls", "is-running"));
            } else {
                PlanOutcome po = plan(command, histBlock);
                intent = po.intent();
                steps = po.steps();
                recorder.send("intent", map("summary", intent.summary(), "entities", intent.entities()));
                recorder.send("phase", map("name", "understand", "state", "done"));

                recorder.send("phase", map("name", "plan", "state", "active"));
                if (session.isConfirmMode() || requiresConfirm(steps)) {
                    steps = awaitPlanApproval(recorder, session, steps);
                }
                total = steps.size();
                recorder.send("status", map("text", "已拆解为 " + total + " 个子任务，开始执行…", "cls", "is-running"));
                for (int i = 0; i < total; i++) {
                    PlanStep s = steps.get(i);
                    recorder.send("step", map("index", i, "total", total,
                            "kind", s.kind(), "tag", s.tag(), "title", s.title()));
                }
                recorder.send("phase", map("name", "plan", "state", "done"));
            }

            /* 阶段三：逐步执行（同 group 的连续 tool 步并行；ReAct 模式逐轮决策；hybrid 计划受阻时转 ReAct） */
            recorder.send("phase", map("name", "execute", "state", "active"));
            if (steps == null) {
                ReactOutcome ro = runReactLoop(recorder, session, command, intent, histBlock,
                        toolResults, null, 0, MAX_REACT_STEPS, false);
                writeOutput = ro.writeOutput();
                total = ro.stepsEmitted();
            } else {
                // 混合模式下记录失败的工具步序号，用于触发"计划受阻 → 转 ReAct"
                Set<Integer> failedSteps = ConcurrentHashMap.newKeySet();
                for (int i = 0; i < steps.size(); ) {
                    checkCancelled(session);
                    int j = parallelEnd(steps, i);
                    if (j - i > 1) {
                        executeParallel(recorder, session, command, intent, histBlock, steps, i, j, toolResults, failedSteps);
                    } else {
                        writeOutput = executeStep(recorder, session, command, intent, histBlock, steps.get(i), i, toolResults, writeOutput, failedSteps);
                    }
                    if (session.isClarifyPaused()) {
                        break;
                    }
                    // 混合模式：报告类任务格式固定不切换；工具未命中/失败时放弃剩余计划，转 ReAct 自主找路
                    int batchFrom = i;
                    int batchTo = j;
                    if (hybridEnabled() && detectReportKind(command) == null
                            && failedSteps.stream().anyMatch(k -> k >= batchFrom && k < batchTo)) {
                        recorder.send("reason", map("index", i, "line",
                                "计划步骤未命中或失败，剩余计划步骤中止，转入 ReAct 自主决策"));
                        recorder.send("status", map("text", "计划受阻，切换自主模式继续…", "cls", "is-running"));
                        ReactOutcome ro = runReactLoop(recorder, session, command, intent, histBlock,
                                toolResults, writeOutput, j, MAX_HYBRID_REACT_STEPS, true);
                        writeOutput = ro.writeOutput();
                        total = j + ro.stepsEmitted();
                        break;
                    }
                    i = j;
                }
            }
            recorder.send("phase", map("name", "execute", "state", "done"));

            /* 澄清暂停：工具未直接命中并给出候选 → 不再执行剩余步骤、不做 LLM 汇总，
               等待用户在前端候选卡上点选（点选即以候选指令重新发起任务） */
            if (session.isClarifyPaused()) {
                recorder.send("status", map("text", "未直接命中，已给出候选，等待选择", "cls", "is-done"));
                String question = session.clarifyQuestion();
                recorder.send("done", map(
                        "summary", question,
                        "output", "",
                        "meta", List.of("已暂停 · 等待选择候选")));
                recorder.finish("done", question, "");
                completeEmitter(session);
                return;
            }

            /* 阶段四：结果汇总 */
            recorder.send("phase", map("name", "merge", "state", "active"));
            recorder.send("status", map("text", "正在汇总各任务结果…", "cls", "is-running"));
            String[] finalOut = mergeFinal(command, histBlock, toolResults, writeOutput);
            recorder.send("done", map(
                    "summary", finalOut[0],
                    "output", finalOut[1],
                    "meta", buildMeta(total, toolResults)));

            recorder.send("phase", map("name", "merge", "state", "done"));
            recorder.send("status", map("text", "✓ 执行完成", "cls", "is-done"));
            recorder.finish("done", finalOut[0], finalOut[1]);
            completeEmitter(session);
            // 收尾后异步提取长期记忆（LLM 模式且开启自动提取时），不阻塞本次任务的返回
            scheduleMemoryExtraction(command, finalOut[1]);
        } catch (CancelledException ex) {
            // 取消也发 done 事件：携带已产出的部分内容与 cancelled 标记，前端出收尾卡
            recorder.send("status", map("text", "已手动停止", "cls", "is-done"));
            recorder.send("done", map(
                    "summary", "已手动停止",
                    "output", writeOutput == null ? "" : writeOutput,
                    "meta", List.of("已手动停止 · 已完成的部分保留如下"),
                    "cancelled", true));
            recorder.finish("cancelled", "已手动停止", writeOutput == null ? "" : writeOutput);
            completeEmitter(session);
        } catch (Exception ex) {
            log.error("任务执行异常", ex);
            recorder.send("status", map("text", "✕ 任务执行异常，请重试", "cls", "is-error"));
            recorder.finish("error", "任务执行异常", "");
            completeEmitter(session);
        }
    }

    /* ================= 意图分析 + 任务规划 ================= */

    private record Intent(String summary, List<String> entities) {
    }

    /** 规划结果：意图与步骤同一次 LLM 调用产出 */
    private record PlanOutcome(Intent intent, List<PlanStep> steps) {
    }

    /** 规划 + 意图一次 LLM 调用完成；失败回退启发式 */
    private PlanOutcome plan(String command, String histBlock) {
        // GitLab 工作报告是专线：时间窗必须由代码解析（LLM 会自行编造日期参数，曾把年份写错），直接走启发式规划
        if (detectReportKind(command) != null) {
            return new PlanOutcome(heuristicIntent(command), heuristicPlan(command));
        }
        if (llmClient.isEnabled()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    PlanOutcome po = llmPlan(command, histBlock);
                    if (!po.steps().isEmpty()) {
                        return po;
                    }
                } catch (Exception ex) {
                    log.warn("LLM 规划第 {} 次失败: {}", attempt + 1, ex.getMessage());
                }
            }
        }
        return new PlanOutcome(heuristicIntent(command), heuristicPlan(command));
    }

    /** 启发式意图抽取：关键词类别（仅在 LLM 不可用或规划失败时兜底） */
    private Intent heuristicIntent(String command) {
        List<String> entities = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        if (detectReportKind(command) != null) categories.add("GitLab 工作报告");
        entities.addAll(categories);

        String summary;
        if (categories.isEmpty() && entities.isEmpty()) {
            summary = "通用内容任务：" + truncate(command, 24);
        } else {
            summary = "识别到" + String.join("、", entities) + " 相关任务";
        }
        return new Intent(summary, entities);
    }

    /* ================= 任务规划 ================= */

    private PlanOutcome llmPlan(String command, String histBlock) {
        String system = String.format(PLAN_SYSTEM_TEMPLATE, toolRegistry.describeForPrompt());
        String content = llmClient.chatJson(system, "用户指令：" + command + histBlock);
        JsonNode node = readJsonObject(content);
        String summary = node.path("summary").asText("");
        List<String> entities = new ArrayList<>();
        node.path("entities").forEach(e -> {
            String t = e.asText().trim();
            if (!t.isEmpty()) {
                entities.add(t);
            }
        });
        Intent intent = summary.isBlank() ? heuristicIntent(command) : new Intent(summary, entities);

        JsonNode arr = node.has("steps") && node.path("steps").isArray()
                ? node.path("steps")
                : fallbackArray(content);
        List<PlanStep> steps = new ArrayList<>();
        if (arr == null) {
            return new PlanOutcome(intent, steps);
        }
        for (JsonNode n : arr) {
            String kind = n.path("kind").asText("tool");
            String tag = n.path("tag").asText(kind);
            String title = n.path("title").asText("子任务");
            ToolCall tool = null;
            JsonNode t = n.path("tool");
            if (t.isObject() && !t.path("name").asText("").isBlank()) {
                Map<String, Object> args = new LinkedHashMap<>();
                t.path("args").fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
                tool = new ToolCall(t.path("name").asText(), args);
            }
            List<String> lines = "think".equals(kind) ? List.of() : null;
            int group = Math.max(0, n.path("group").asInt(0));
            steps.add(new PlanStep(kind, tag, title, tool, lines, group, false));
        }
        return new PlanOutcome(intent, steps);
    }

    /* ================= 计划放行（Human-in-the-loop） ================= */

    /** 计划中任一工具是写操作时，无论是否开启 confirm 模式都强制人工放行 */
    private boolean requiresConfirm(List<PlanStep> steps) {
        for (PlanStep s : steps) {
            if (s.tool() != null && toolRequiresConfirm(s.tool().name())) {
                return true;
            }
        }
        return false;
    }

    private boolean toolRequiresConfirm(String toolName) {
        Tool t = toolRegistry.get(toolName);
        return t != null && t.requiresConfirm();
    }

    /** confirm 模式：把计划推给前端等待确认/编辑，返回过滤 skip 后的有效计划 */
    private List<PlanStep> awaitPlanApproval(RunRecorder recorder, RunSession session, List<PlanStep> steps) {
        List<Object> proposal = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            PlanStep s = steps.get(i);
            Map<String, Object> m = map("index", i, "kind", s.kind(), "tag", s.tag(), "title", s.title(),
                    "group", s.group(), "skip", false);
            if (s.tool() != null) {
                m.put("tool", map("name", s.tool().name(), "args", s.tool().args(),
                        "requiresConfirm", toolRequiresConfirm(s.tool().name())));
            }
            proposal.add(m);
        }
        recorder.send("plan-proposal", map("steps", proposal));
        recorder.send("status", map("text", "等待确认执行计划…", "cls", "is-running"));
        try {
            List<PlanStep> confirmed = session.awaitConfirm().get(CONFIRM_TIMEOUT_MIN, TimeUnit.MINUTES);
            List<PlanStep> effective = new ArrayList<>();
            for (PlanStep s : confirmed) {
                if (!s.skip()) {
                    effective.add(s);
                }
            }
            recorder.send("plan-confirmed", map("total", effective.size()));
            recorder.send("status", map("text", "计划已确认，开始执行…", "cls", "is-running"));
            return effective;
        } catch (TimeoutException ex) {
            throw new CancelledException();
        } catch (Exception ex) {
            // 等待期间被取消或会话结束
            throw new CancelledException();
        }
    }

    /* ================= 并行执行 ================= */

    /** 从 from 开始的区段 Exclusive 结束位置：同 group 的连续 tool 步构成一个并行区段 */
    private static int parallelEnd(List<PlanStep> steps, int from) {
        PlanStep first = steps.get(from);
        if (!"tool".equals(first.kind()) || first.tool() == null || first.group() <= 0) {
            return from + 1;
        }
        int j = from + 1;
        while (j < steps.size()) {
            PlanStep s = steps.get(j);
            if (!"tool".equals(s.kind()) || s.tool() == null || s.group() != first.group()) {
                break;
            }
            j++;
        }
        return j;
    }

    /** 并行执行 [from, to) 的 tool 步：事件按 index 各自推送，结果按顺序合并回主结果表 */
    private void executeParallel(RunRecorder recorder, RunSession session, String command, Intent intent, String histBlock,
                                 List<PlanStep> steps, int from, int to, Map<String, String> toolResults,
                                 Set<Integer> failedSteps) {
        List<Integer> indexes = new ArrayList<>();
        for (int k = from; k < to; k++) {
            indexes.add(k);
        }
        for (int start = 0; start < indexes.size(); start += MAX_PARALLEL) {
            checkCancelled(session);
            List<Integer> batch = indexes.subList(start, Math.min(start + MAX_PARALLEL, indexes.size()));
            List<Callable<Map<String, String>>> jobs = new ArrayList<>();
            for (Integer k : batch) {
                jobs.add(() -> {
                    Map<String, String> partial = new LinkedHashMap<>();
                    executeStep(recorder, session, command, intent, histBlock, steps.get(k), k, partial, null, failedSteps);
                    return partial;
                });
            }
            try {
                List<Future<Map<String, String>>> futures = executor.invokeAll(jobs);
                for (Future<Map<String, String>> f : futures) {
                    toolResults.putAll(f.get());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(ex.getCause());
            }
        }
    }

    /* ================= ReAct 自主循环 ================= */

    /** ReAct 单轮决策结果 */
    private record ReactDecision(String action, ToolCall tool, String note) {
    }

    /** 问 LLM 决定下一步动作；失败返回 null（由调用方收敛循环）。hybridContext 为混合模式转场：说明计划已中断 */
    private ReactDecision reactDecide(String command, String histBlock, Map<String, String> toolResults, boolean hybridContext) {
        try {
            String system = String.format(REACT_SYSTEM_TEMPLATE, toolRegistry.describeForPrompt());
            String hybridNote = hybridContext
                    ? "\n注意：原定计划因工具未命中/失败而中断，请基于已获得的结果自主决定如何完成目标。\n"
                    : "";
            String content = llmClient.chatJson(system,
                    "任务目标：" + command + "\n已获得的工具结果：\n" + toolSummary(toolResults) + histBlock
                            + hybridNote + "\n请决定下一步动作。");
            JsonNode node = readJsonObject(content);
            String action = node.path("action").asText("write").toLowerCase();
            if (action.isBlank()) {
                action = "write";
            }
            ToolCall tool = null;
            JsonNode t = node.path("tool");
            if (t.isObject() && !t.path("name").asText("").isBlank()) {
                Map<String, Object> args = new LinkedHashMap<>();
                t.path("args").fields().forEachRemaining(e -> args.put(e.getKey(), e.getValue().asText()));
                tool = new ToolCall(t.path("name").asText(), args);
            }
            return new ReactDecision(action, tool, node.path("note").asText(""));
        } catch (Exception ex) {
            log.warn("ReAct 决策失败: {}", ex.getMessage());
            return null;
        }
    }

    /** ReAct 循环产出：write 步内容 + 实际发出的步骤数（供前端 total 与混合模式计数） */
    private record ReactOutcome(String writeOutput, int stepsEmitted) {
    }

    /**
     * ReAct 自主循环（纯 react 模式与混合模式转场共用）：
     * 逐轮问 LLM 决定 tool/write/finish，步数从 startIdx 起编（混合模式接续计划已完成的步数），
     * 预算 budget 步；循环结束仍无交付物时兜底补一个 write 步。
     */
    private ReactOutcome runReactLoop(RunRecorder recorder, RunSession session, String command, Intent intent,
                                      String histBlock, Map<String, String> toolResults, String writeOutputIn,
                                      int startIdx, int budget, boolean hybridContext) {
        String writeOutput = writeOutputIn;
        boolean wrote = writeOutput != null;
        int stepIdx = startIdx;
        int emitted = 0;
        while (stepIdx < startIdx + budget) {
            checkCancelled(session);
            ReactDecision d = reactDecide(command, histBlock, toolResults, hybridContext);
            if (d == null || "finish".equals(d.action())) {
                break;
            }
            boolean isWrite = "write".equals(d.action()) || d.tool() == null;
            String title = d.note() == null || d.note().isBlank()
                    ? (isWrite ? "生成最终交付内容" : "调用工具取数") : d.note();
            PlanStep s = isWrite ? PlanStep.write(title) : PlanStep.tool(title, d.tool());
            recorder.send("step", map("index", stepIdx, "total", startIdx + budget,
                    "kind", s.kind(), "tag", s.tag(), "title", s.title()));
            emitted++;
            String out = executeStep(recorder, session, command, intent, histBlock, s, stepIdx, toolResults, writeOutput, null);
            if (session.isClarifyPaused()) {
                break;
            }
            if (isWrite) {
                writeOutput = out;
                wrote = true;
                break;
            }
            stepIdx++;
        }
        if (!wrote && !session.isClarifyPaused()) {
            // 决策循环未产出交付物：兜底补一个 write 步
            recorder.send("step", map("index", stepIdx, "total", startIdx + budget,
                    "kind", "write", "tag", "内容生成", "title", "汇总生成最终交付内容"));
            emitted++;
            writeOutput = executeStep(recorder, session, command, intent, histBlock,
                    PlanStep.write("汇总生成最终交付内容"), stepIdx, toolResults, writeOutput, null);
        }
        return new ReactOutcome(writeOutput, Math.max(emitted, 1));
    }

    /**
     * 混合模式的「计划受阻」判定：工具返回 note 型降级结果（未命中/未配置/查询失败）。
     * clarify 候选不算失败——它有独立的暂停-点选重跑流程，优先级更高。
     */
    static boolean isStepFailed(ToolResult tr) {
        return tr != null
                && tr.clarify() == null
                && "json".equals(tr.resultType())
                && tr.result() != null
                && tr.result().containsKey("note");
    }

    /** 通用启发式拆解：任何指令都能得到合理计划 */
    private List<PlanStep> heuristicPlan(String command) {
        List<PlanStep> steps = new ArrayList<>();

        // 0. GitLab 工作报告：拉提交 → 归纳 → 成稿，专线处理
        String reportKind = detectReportKind(command);
        if (reportKind != null) {
            boolean weekly = "weekly".equals(reportKind);
            GitLabTool.Window win = reportWindow(command, weekly);
            steps.add(PlanStep.tool("拉取我的 GitLab 提交记录（" + win.scopeZh() + "）",
                    new ToolCall("gitlab.query", Map.of("type", "mine",
                            "since", win.since().toString(), "until", win.until().toString()))));
            steps.add(PlanStep.think("归纳提交记录 · 提炼工作主线"));
            steps.add(PlanStep.write("生成工作" + (weekly ? "周报" : "日报")));
            return steps;
        }

        // 0.5 链路分析专线：trace 分析 + 变更关联（GitLab 可用时自动衔接）→ 归纳 → 成稿
        String traceId = extractTraceId(command);
        if (traceId != null || command.contains("链路")) {
            Map<String, Object> traceArgs = traceId == null ? Map.of() : Map.of("traceId", traceId);
            steps.add(PlanStep.tool("分析 SigNoz 链路", new ToolCall("signoz.trace", traceArgs)));
            if (gitlabConfigured()) {
                steps.add(PlanStep.tool("关联故障前的代码变更（谁改坏的）", new ToolCall("gitlab.changes", traceArgs)));
            }
            steps.add(PlanStep.think("结合链路根因与嫌疑变更，梳理因果链"));
            steps.add(PlanStep.write("输出链路分析结论与变更关联报告"));
            return steps;
        }

        // 0.8 知识库专线：提到知识库/资料/文档且库非空 → 检索 → 归纳 → 成稿
        if (kbNonEmpty() && (command.contains("知识库") || command.contains("资料") || command.contains("文档")
                || command.contains("规范手册") || command.contains("操作手册"))) {
            steps.add(PlanStep.tool("检索个人知识库", new ToolCall("kb.query",
                    Map.of("mode", "search", "query", command))));
            steps.add(PlanStep.think("归纳知识库命中内容 · 关联问题"));
            steps.add(PlanStep.write("基于知识库内容作答（注明来源文件）"));
            return steps;
        }

        // 1. 关键词驱动的工具步
        boolean wantsGitlab = command.toLowerCase().contains("gitlab")
                || command.contains("合并请求") || command.contains("流水线") || command.contains("构建状态");
        if (wantsGitlab || command.toLowerCase().contains("issue") || command.contains("缺陷单") || command.contains("任务单")) {
            steps.add(PlanStep.tool("查询公司 GitLab 项目动态", new ToolCall("gitlab.query", Map.of())));
        }

        // 2. 通用分析步
        steps.add(PlanStep.think("提炼任务要点 · 确定产出方向"));
        // 3. 通用生成步
        steps.add(PlanStep.write("生成「" + truncate(command, 16) + "」交付内容"));
        return steps;
    }

    /** 识别 GitLab 工作报告意图：与提交/代码相关且提到日报或周报；返回 daily/weekly/null */
    private String detectReportKind(String command) {        String lower = command.toLowerCase();
        boolean gitRelated = lower.contains("gitlab") || lower.contains("commit")
                || command.contains("提交") || command.contains("代码");
        if (!gitRelated) {
            return null;
        }
        if (command.contains("周报") || command.contains("本周总结") || command.contains("这周总结")) {
            return "weekly";
        }
        if (command.contains("日报") || command.contains("今日总结")
                || (command.contains("今天") && command.contains("总结"))) {
            return "daily";
        }
        return null;
    }

    /** 指令里的 32 位十六进制即 trace ID；没有返回 null */
    private static String extractTraceId(String command) {
        if (command == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b([0-9a-fA-F]{32})\\b").matcher(command);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    /** GitLab 是否已配置（决定链路分析后能否自动衔接变更关联） */
    private boolean gitlabConfigured() {
        return toolRegistry.get("gitlab.query") instanceof GitLabTool g && g.isConfigured();
    }

    /** 知识库是否非空（决定知识库类提问能否直接加检索步） */
    private boolean kbNonEmpty() {
        return toolRegistry.get("kb.query") instanceof com.agentflow.kb.KbSearchTool kb && !kb.isEmpty();
    }

    /* ================= 步骤执行 ================= */

    /** 返回 write 步生成的内容（供汇总复用）；failedSteps 非空时记录 note 型失败步的序号（混合模式切换用） */
    private String executeStep(RunRecorder recorder, RunSession session, String command, Intent intent, String histBlock,
                               PlanStep s, int index, Map<String, String> toolResults,
                               String writeOutput, Set<Integer> failedSteps) {
        checkCancelled(session);
        recorder.send("step-state", map("index", index, "state", "running"));
        recorder.send("status", map("text", "正在执行 · " + s.title(), "cls", "is-running"));

        if ("tool".equals(s.kind()) && s.tool() != null) {
            recorder.send("tool", map("index", index, "name", s.tool().name(), "args", s.tool().args()));

            ToolResult tr = null;
            try {
                tr = toolRegistry.execute(s.tool().name(), s.tool().args(), command);
            } catch (Exception ex) {
                log.warn("工具 {} 执行失败: {}", s.tool().name(), ex.getMessage());
            }
            if (tr == null) {
                tr = ToolResult.note("工具 " + s.tool().name() + " 不可用");
            }
            if (failedSteps != null && isStepFailed(tr)) {
                failedSteps.add(index);
            }
            // 摘要 + 具体数据一并交给后续 LLM 推理/汇总，避免模型只凭一句话编造细节（周报素材较长，放宽截断）
            String detail = tr.list() == null || tr.list().isEmpty()
                    ? String.valueOf(tr.result() == null ? Map.of() : tr.result())
                    : String.join("；", tr.list());
            String summary = (tr.summary() == null ? "" : tr.summary()) + "｜" + truncate(detail, 1600);
            // 同名工具可能被规划多次，key 带步骤序号避免相互覆盖
            toolResults.put(s.tool().name() + "#" + index, summary);

            recorder.send("result", map("index", index, "resultType", tr.resultType(),
                    "result", tr.result() == null ? Map.of() : tr.result(),
                    "list", tr.list() == null ? List.of() : tr.list()));
            // 未命中但带候选：推 clarify 事件，前端渲染选项卡（点选即改写重跑）；
            // 同时暂停本任务剩余步骤，避免在目标缺失的情况下继续推理/汇总
            if (tr.clarify() != null && tr.clarify().options() != null && !tr.clarify().options().isEmpty()) {
                recorder.send("clarify", map("question", tr.clarify().question(),
                        "options", tr.clarify().options()));
                session.pauseForClarify(tr.clarify().question());
            }
            recorder.send("step-state", map("index", index, "state", "done"));
            return writeOutput;
        }

        if ("think".equals(s.kind())) {
            List<String> lines = s.lines();
            if (llmClient.isEnabled()) {
                try {
                    String reasoning = llmClient.chat(THINK_SYSTEM,
                            "用户指令：" + command + "\n子任务：" + s.title()
                                    + "\n意图：" + intent.summary()
                                    + "\n已获得的工具结果：\n" + toolSummary(toolResults) + histBlock);
                    List<String> generated = normalizeReasonLines(splitLines(reasoning));
                    if (!generated.isEmpty()) {
                        lines = generated;
                    }
                } catch (Exception ex) {
                    log.warn("LLM 推理失败，使用启发式分析: {}", ex.getMessage());
                }
            }
            if (lines == null || lines.isEmpty()) {
                lines = heuristicThinkLines(command, intent, toolResults);
            }
            for (String line : lines) {
                recorder.send("reason", map("index", index, "line", line));
            }
            recorder.send("step-state", map("index", index, "state", "done"));
            return writeOutput;
        }

        if ("write".equals(s.kind())) {
            String reportKind = detectReportKind(command);
            GitLabTool.Window reportWin = reportKind != null
                    ? reportWindow(command, "weekly".equals(reportKind)) : null;
            String system = reportWin != null ? reportWriteSystem(reportKind, reportWin) : WRITE_SYSTEM;
            String userPrompt = "用户指令：" + command + "\n意图：" + intent.summary()
                    + "\n已获得的工具结果：\n" + toolSummary(toolResults) + histBlock
                    + "\n请生成最终成品内容。";

            String content = null;
            // 报告时间窗内没有提交素材时不走 LLM（避免自由发挥破坏固定格式），直接用固定格式模板
            boolean reportHasMaterial = reportWin == null
                    || toolResults.values().stream().anyMatch(v -> v != null && v.contains("共提交"));
            if (llmClient.isEnabled() && reportHasMaterial) {
                try {
                    // 流式生成：增量片段实时推送，最终以完整 result 事件为准；取消时回调内抛出中断信号
                    content = llmClient.chatStream(system, userPrompt, piece -> {
                        checkCancelled(session);
                        recorder.streamDelta(index, piece);
                    });
                } catch (Exception ex) {
                    log.warn("LLM 流式生成失败，尝试非流式重试: {}", ex.getMessage());
                } finally {
                    recorder.flushStream(index);
                }
                checkCancelled(session);
                if (content == null || content.isBlank()) {
                    // 流式异常或空响应（多为瞬时抖动），退化为非流式整段生成
                    try {
                        content = llmClient.chat(system, userPrompt);
                    } catch (Exception ex2) {
                        log.warn("LLM 非流式生成也失败，使用模板内容: {}", ex2.getMessage());
                    }
                }
            }
            boolean llmGenerated = content != null && !content.isBlank();
            if (!llmGenerated) {
                content = reportKind != null
                        ? templateReport(toolResults, "weekly".equals(reportKind), reportWin)
                        : templateWrite(command, intent, toolResults);
            }
            if (reportKind != null) {
                // 报告要求纯文本，兜底清掉模型偶尔混入的 Markdown 标记
                content = stripMarkdown(content);
            }
            recorder.send("result", map("index", index, "resultType", "copy",
                    "result", map("versions", List.of(map("tag", llmGenerated ? "AI 生成" : "模拟模式 · 模板生成", "text", content))),
                    "list", List.of()));
            recorder.send("step-state", map("index", index, "state", "done"));
            return content;
        }

        /* 其余 kind：通用占位 */
        recorder.send("result", map("index", index, "resultType", "json",
                "result", Map.of("note", "已跳过不支持的步骤类型：" + s.kind()), "list", List.of()));
        recorder.send("step-state", map("index", index, "state", "done"));
        return writeOutput;
    }

    /** 模拟模式的要点分析：基于指令与已获数据的确定性推导 */
    private List<String> heuristicThinkLines(String command, Intent intent, Map<String, String> toolResults) {
        List<String> lines = new ArrayList<>();
        lines.add("目标拆解：" + truncate(command, 30) + "，需要产出可直接使用的结果");
        if (!intent.entities().isEmpty()) {
            lines.add("关键要素：" + String.join("、", intent.entities));
        }
        for (Map.Entry<String, String> e : toolResults.entrySet()) {
            lines.add("数据解读：" + truncate(e.getValue(), 60));
        }
        if (toolResults.isEmpty()) {
            lines.add("数据解读：本任务不依赖外部工具数据，以指令本身的要求为准");
        }
        lines.add("思路选择：整合以上信息，按指令要求的格式与口吻组织内容");
        lines.add("结论：进入生成阶段，产出围绕任务目标的完整交付内容");
        return lines;
    }

    /** 模拟模式的内容生成：组合真实工具数据 + 指令，不虚构事实 */
    private String templateWrite(String command, Intent intent, Map<String, String> toolResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("围绕「").append(truncate(command, 30)).append("」整理如下：\n");
        if (!toolResults.isEmpty()) {
            for (Map.Entry<String, String> e : toolResults.entrySet()) {
                sb.append("· ").append(e.getValue()).append("\n");
            }
        }
        sb.append("· 建议结合上述信息安排后续行动\n");
        sb.append("\n（模拟模式：由模板整合工具真实数据生成；配置 DEEPSEEK_API_KEY 后将由 LLM 生成完整定制内容）");
        return sb.toString();
    }

    /* ================= GitLab 工作报告 ================= */

    /**
     * 报告时间窗：识别指令中的时间词（今天/昨天/本周/上周/近N天/具体日期或区间）；
     * 未识别时日报=当天（晨报指令带「昨天」会被识别）、周报=本周兜底。
     */
    private static GitLabTool.Window reportWindow(String command, boolean weekly) {
        GitLabTool.Window w = GitLabTool.parseWindow(command);
        if (w != null) {
            return w;
        }
        LocalDate today = LocalDate.now();
        return weekly
                ? GitLabTool.window(today.minusDays(6), today, "本周")
                : GitLabTool.window(today, today, "今天");
    }

    /** 报告生成 prompt：给出行结构示例 + 日期星期对照表 + 硬性约束，按「日期（星期）+ 当日工作主线」逐行排点 */
    private String reportWriteSystem(String kind, GitLabTool.Window w) {
        boolean weekly = "weekly".equals(kind);
        String kindZh = weekly ? "周报" : "日报";
        String planZh = weekly ? "下周" : "明日";
        String scope = w.since().equals(w.until())
                ? w.since() + "（" + weekdayZh(w.since()) + "）"
                : w.since() + "（" + weekdayZh(w.since()) + "）至 " + w.until() + "（" + weekdayZh(w.until()) + "）";
        // 模型不会算星期，直接给对照表
        StringBuilder lookup = new StringBuilder();
        for (LocalDate d = w.since(); !d.isAfter(w.until()); d = d.plusDays(1)) {
            if (lookup.length() > 0) {
                lookup.append("，");
            }
            lookup.append(String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth())).append("=").append(weekdayZh(d));
        }
        String sample = weekly
                ? "【" + reportDept + "】个人效能周报\n"
                  + "2026-09-07（周一）\n"
                  + "1. 完成数据权限功能开发（权限配置组件开发与 UI 调整）\n"
                  + "2. 调整运行态组件 UI\n"
                  + "2026-09-08（周二）\n"
                  + "1. 开发通用记录操作中心（通用操作记录查询组件）\n"
                  + "2. 完成数据权限联调\n"
                  + "【下周计划】\n1. 跟进数据权限合入后的联调验证"
                : "【" + reportDept + "】个人效能日报\n"
                  + "2026-09-08（周二）\n"
                  + "1. 完成数据权限功能开发（权限配置组件开发与 UI 调整）\n"
                  + "2. 调整运行态组件 UI\n"
                  + "【明日计划】\n1. 跟进数据权限合入后的联调验证";
        return "你是 AgentFlow 的工作报告生成模块。请基于提供的 Git 提交记录，严格按下面的行结构输出工作报告。\n"
                + "输出结构示例（示例中的日期与内容仅示意格式，必须替换为提交记录中的真实工作，禁止照抄示例文字）：\n"
                + sample + "\n"
                + "硬性要求：\n"
                + "1. 第一行固定为「【" + reportDept + "】个人效能" + kindZh + "」，一字不改\n"
                + "2. 时间窗内每个有提交的日期独占一行，该行只写「日期（星期）」，如 2026-09-08（周二），星期从对照表取，日期按先后排列，日期行不写任何工作内容\n"
                + "3. 日期行下方逐条列出当天工作：每条独占一行，以“1. ”“2. ”“3. ”编号且每天从 1 重新开始；同一天归纳为 2~5 条工作主线，可带中文圆括号补充细节，禁止逐条罗列原始提交\n"
                + "4. Merge/分支合并/revert 等同步类提交一律忽略，不得出现在报告中；禁止出现分支名、commit 哈希、代码文件名、命令行符号等工程噪音\n"
                + "5. 每条主线用中文动词开头（完成/新增/修复/优化/联调/配置），面向汇报对象可读；代码前缀如 feat(todo) 应转述为「待办模块」这类中文模块名\n"
                + "6. 最后是「【" + planZh + "计划】」单独一行，其下 1~3 条计划，每条独占一行并以“1. ”“2. ”编号（基于已有工作合理延伸，没有依据时只写“1. 待补充”）\n"
                + "7. 全文必须是纯文本：禁止任何 Markdown 标记（**、#、-、*、` 等），不要“提交人”行、不要总结段、不要任何解释\n"
                + "报告时间窗：" + scope + "；提交记录中的 MM-dd 对应以下星期（必须使用括注的星期）：\n"
                + lookup + "\n"
                + "严禁编造提交记录中没有的工作内容。";
    }

    /** 报告纯文本兜底：去掉加粗/行内代码标记与行首项目符号 */
    private static String stripMarkdown(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("**", "")
                .replace("`", "")
                .replaceAll("(?m)^[-*•]\\s+", "");
    }

    /** 模拟模式的报告模板：按天归组提交，过滤 Merge 噪音、剥离代码前缀后逐条中文分点 */
    private String templateReport(Map<String, String> toolResults, boolean weekly, GitLabTool.Window w) {
        // 从工具结果里抽出 "MM-dd HH:mm · 标题" 形式的提交行，按日期归组
        Map<LocalDate, List<String>> byDay = new TreeMap<>();
        for (String v : toolResults.values()) {
            int bar = v.indexOf('｜');
            String detail = bar >= 0 ? v.substring(bar + 1) : v;
            for (String line : detail.split("；")) {
                String t = line.trim();
                LocalDate d = matchDate(t, w.since(), w.until());
                if (d != null) {
                    int dot = t.indexOf('·');
                    String title = dot >= 0 ? t.substring(dot + 1).trim() : t;
                    byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(title);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(reportDept).append("】个人效能").append(weekly ? "周报" : "日报").append("\n");
        boolean any = false;
        for (Map.Entry<LocalDate, List<String>> e : byDay.entrySet()) {
            List<String> items = dayItems(e.getValue());
            if (items.isEmpty()) {
                continue;
            }
            any = true;
            // 日期独占一行，当日工作逐条分点，编号每天从 1 开始
            sb.append(e.getKey()).append("（").append(weekdayZh(e.getKey())).append("）\n");
            for (int i = 0; i < items.size(); i++) {
                sb.append(i + 1).append(". ").append(items.get(i)).append("\n");
            }
        }
        if (!any) {
            // 与有数据时同构：单日一行日期，区间给起止两天
            if (w.since().equals(w.until())) {
                sb.append(w.since()).append("（").append(weekdayZh(w.since())).append("）暂无提交记录\n");
            } else {
                sb.append(w.since()).append("（").append(weekdayZh(w.since())).append("）至 ")
                        .append(w.until()).append("（").append(weekdayZh(w.until())).append("）暂无提交记录\n");
            }
        }
        sb.append("【").append(weekly ? "下周" : "明日").append("计划】\n1. 待补充\n");
        if (!llmClient.isEnabled()) {
            sb.append("\n（模拟模式：由模板基于真实 GitLab 提交记录整理生成；配置 DEEPSEEK_API_KEY 后将由 LLM 归纳生成完整报告）");
        }
        return sb.toString();
    }

    /** 单日最多列出的工作条目数（超出部分不再展开，保持报告篇幅可控） */
    private static final int MAX_DAY_ITEMS = 6;

    /**
     * 单日提交整理为中文条目列表：过滤 Merge/分支同步噪音，剥离 conventional commit 前缀
     * （feat(todo): → 待办模块），去重后按顺序最多 6 条。
     */
    static List<String> dayItems(List<String> titles) {
        List<String> items = new ArrayList<>();
        for (String raw : titles) {
            String cleaned = cleanCommitTitle(raw);
            if (cleaned == null) {
                continue;
            }
            if (!items.contains(cleaned)) {
                items.add(cleaned);
            }
        }
        return items.size() > MAX_DAY_ITEMS
                ? new ArrayList<>(items.subList(0, MAX_DAY_ITEMS))
                : items;
    }

    /** 提交标题清洗：Merge/分支同步类返回 null；conventional 前缀转为「模块：描述」 */
    static String cleanCommitTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = title.trim();
        if (t.isEmpty()) {
            return null;
        }
        String lower = t.toLowerCase();
        if (lower.startsWith("merge ") || lower.startsWith("merged ") || lower.startsWith("revert \"merge")
                || t.contains(" into '") || t.equals(".")) {
            return null;
        }
        // feat(todo): 描述 / fix: 描述 → 待办模块：描述 / 描述（常见 scope 转中文名，未知的保留原文）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(feat|fix|docs|style|refactor|perf|test|chore|build|ci|release)(?:\\(([^)]+)\\))?!?:\\s*(.+)$")
                .matcher(t);
        if (m.matches()) {
            String scope = m.group(2);
            String desc = m.group(3).trim();
            if (desc.startsWith("Merge") || desc.toLowerCase().contains(" into '")) {
                return null;
            }
            if (scope == null || scope.isBlank()) {
                return desc;
            }
            String scopeZh = SCOPE_ZH.getOrDefault(scope.toLowerCase(), scope);
            return scopeZh + "：" + desc;
        }
        if (lower.startsWith("revert")) {
            return null;
        }
        return t;
    }

    /** 常见提交 scope 的中文名（报告可读性）；未收录的保留原文 */
    private static final java.util.Map<String, String> SCOPE_ZH = java.util.Map.of(
            "todo", "待办模块",
            "portal", "门户模块",
            "common", "公共模块",
            "user", "用户模块",
            "auth", "认证模块",
            "report", "报表模块",
            "flow", "流程模块");

    /** 把提交行开头的 MM-dd 匹配到报告时间窗内的具体日期；非提交行返回 null */
    private static LocalDate matchDate(String line, LocalDate since, LocalDate until) {
        if (line.length() < 5 || line.charAt(2) != '-') {
            return null;
        }
        String mmdd = line.substring(0, 5);
        for (LocalDate d = since; !d.isAfter(until); d = d.plusDays(1)) {
            if (String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth()).equals(mmdd)) {
                return d;
            }
        }
        return null;
    }

    private static String weekdayZh(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            default -> "周日";
        };
    }

    /* ================= 汇总 ================= */

    private String[] mergeFinal(String command, String histBlock, Map<String, String> toolResults, String writeOutput) {
        // write 步已产出成品时直接采用原文，避免汇总改写破坏交付物格式；总结本地取首行，省一次 LLM 调用
        if (writeOutput != null && !writeOutput.isBlank()) {
            String summary = null;
            for (String l : writeOutput.split("\n")) {
                if (!l.isBlank()) {
                    summary = truncate(l.trim(), 40);
                    break;
                }
            }
            if (summary == null || summary.isBlank()) {
                summary = "已完成 " + (toolResults.size() + 2) + " 个子任务，交付内容如下";
            }
            return new String[]{summary, writeOutput};
        }
        if (llmClient.isEnabled()) {
            try {
                String content = llmClient.chat(FINAL_SYSTEM,
                        "用户指令：" + command + "\n各子任务执行结果：\n" + toolSummary(toolResults)
                                + "\n生成步内容：\n" + (writeOutput == null ? "（无）" : writeOutput)
                                + histBlock
                                + "\n请按格式汇总输出。");
                List<String> cleaned = new ArrayList<>();
                for (String l : content.split("\n")) {
                    if (!l.isBlank()) cleaned.add(l.trim());
                }
                if (!cleaned.isEmpty()) {
                    String summary = cleaned.get(0);
                    String output = cleaned.size() > 1
                            ? String.join("\n", cleaned.subList(1, cleaned.size())) : summary;
                    return new String[]{summary, output};
                }
            } catch (Exception ex) {
                log.warn("LLM 汇总失败，使用默认汇总: {}", ex.getMessage());
            }
        }
        String summary = "已完成 " + (toolResults.size() + 2) + " 个子任务，结果如下";
        Intent intent = heuristicIntent(command);
        String output = writeOutput != null ? writeOutput
                : templateWrite(command, intent, toolResults);
        return new String[]{summary, output};
    }

    private List<String> buildMeta(int total, Map<String, String> toolResults) {
        List<String> meta = new ArrayList<>();
        meta.add("子任务 " + total + " 个");
        meta.add("工具调用 " + toolResults.size() + " 次");
        meta.add(llmClient.isEnabled() ? "LLM 动态规划" : "模拟模式 · 启发式规划");
        return meta;
    }

    /* ================= 多轮历史 ================= */

    /** 把历史轮次拼成可注入 prompt 的文本块；无历史返回空串 */
    private static String historyBlock(List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n对话历史（此前轮次的指令与产出，用于理解指代与延续上下文，与当前指令无关时可忽略）：\n");
        int i = 1;
        for (Map<String, String> h : history) {
            sb.append("[").append(i++).append("] 指令：").append(truncate(h.get("command"), 120))
                    .append("\n    产出：").append(truncate(h.get("output"), 500)).append("\n");
        }
        return sb.toString();
    }

    /* ================= 长期记忆 ================= */

    /** 记忆块注入所有 LLM prompt（与对话历史块拼接）；无记忆或总开关关闭返回空串 */
    private String memoryBlock() {
        if (!memoryEnabled) {
            return "";
        }
        List<MemoryStore.MemoryItem> items = memoryStore.list();
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n长期记忆（用户偏好与既定事实，规划参数与内容生成时遵循；与当前指令冲突时以当前指令为准）：\n");
        int n = 0;
        for (MemoryStore.MemoryItem it : items) {
            sb.append("- ").append(truncate(it.content(), 120)).append("\n");
            if (++n >= 20) {
                break;
            }
        }
        return sb.toString();
    }

    /** 显式记忆指令：kind = remember | forget */
    record MemoryCommand(String kind, String content) {
    }

    /** 识别「记住 XXX」「忘记/删除记忆 XXX」指令；普通指令返回 null（不匹配则正常走任务流程） */
    static MemoryCommand parseMemoryCommand(String command) {
        if (command == null) {
            return null;
        }
        String c = command.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^记住[:：,，\\s]*(.{1,200})$").matcher(c);
        if (m.matches() && !m.group(1).isBlank()) {
            return new MemoryCommand("remember", m.group(1).trim());
        }
        m = java.util.regex.Pattern.compile("^(?:忘记|忘掉|删除记忆)[:：\\s]*(.{1,200})$").matcher(c);
        if (m.matches() && !m.group(1).isBlank()) {
            return new MemoryCommand("forget", m.group(1).trim());
        }
        return null;
    }

    /** 记忆快速通道：不走规划不花 LLM，直接增删记忆并以 done 收尾 */
    private void handleMemoryCommand(RunRecorder recorder, RunSession session, MemoryCommand mc) {
        String summary;
        if ("remember".equals(mc.kind())) {
            boolean saved = memoryStore.add(mc.content());
            summary = saved ? "已记住：" + mc.content() : "这条已经在记忆里了：" + mc.content();
        } else {
            List<MemoryStore.MemoryItem> hits = memoryStore.findBySubstring(mc.content());
            if (hits.isEmpty()) {
                summary = "没有找到包含「" + mc.content() + "」的记忆";
            } else {
                for (MemoryStore.MemoryItem h : hits) {
                    memoryStore.delete(h.id());
                }
                summary = "已忘记 " + hits.size() + " 条：" + hits.stream()
                        .map(MemoryStore.MemoryItem::content).reduce((a, b) -> a + "；" + b).orElse("");
            }
        }
        recorder.send("status", map("text", summary, "cls", "is-done"));
        recorder.send("done", map("summary", summary, "output", "", "meta", List.of("长期记忆")));
        recorder.finish("done", summary, "");
        completeEmitter(session);
    }

    /** 任务成功收尾后异步提取记忆：LLM 模式 + 自动提取开关 + 有产出才触发；失败静默 */
    private void scheduleMemoryExtraction(String command, String output) {
        if (!memoryEnabled || !llmClient.isEnabled() || !memoryStore.isAutoExtract()) {
            return;
        }
        if (output == null || output.isBlank() || parseMemoryCommand(command) != null) {
            return;
        }
        executor.submit(() -> {
            try {
                extractMemories(command, output);
            } catch (Exception ex) {
                log.debug("记忆自动提取失败: {}", ex.getMessage());
            }
        });
    }

    private void extractMemories(String command, String output) {
        List<String> existing = memoryStore.list().stream().map(MemoryStore.MemoryItem::content).toList();
        String user = "用户指令：" + truncate(command, 200)
                + "\n执行产出（节选）：\n" + truncate(output, 1200)
                + "\n现有记忆：\n" + (existing.isEmpty() ? "（空）" : String.join("\n", existing))
                + "\n请输出 JSON。";
        String content = llmClient.chatJson(EXTRACT_SYSTEM, user);
        JsonNode node = readJsonObject(content);
        node.path("add").forEach(a -> {
            String v = a.asText("").trim();
            if (!v.isEmpty()) {
                memoryStore.add(v);
            }
        });
        node.path("remove").forEach(r -> {
            String v = r.asText("").trim();
            if (!v.isEmpty()) {
                memoryStore.deleteByContent(v);
            }
        });
    }

    /* ================= 工具方法 ================= */

    private JsonNode readJsonObject(String content) {
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return mapper.readTree(content.substring(start, end + 1));
            }
        } catch (Exception ignored) {
        }
        return mapper.createObjectNode();
    }

    private JsonNode fallbackArray(String content) {
        try {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                return mapper.readTree(content.substring(start, end + 1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String toolSummary(Map<String, String> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "（无工具结果）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : toolResults.entrySet()) {
            sb.append("- ").append(e.getKey()).append(" → ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                out.add(t);
                if (out.size() >= MAX_REASON_LINES) {
                    break;
                }
            }
        }
        return out;
    }

    /** 超长单段（模型未分行）时按句号切分为多行，保证思考过程可读 */
    private static List<String> normalizeReasonLines(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.length() <= 60) {
                out.add(line);
            } else {
                for (String seg : line.split("(?<=[。；;])")) {
                    String t = seg.trim();
                    if (!t.isEmpty()) out.add(t);
                    if (out.size() >= MAX_REASON_LINES) return out;
                }
            }
            if (out.size() >= MAX_REASON_LINES) break;
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> map(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /* ================= SSE 发送 + 持久化 ================= */

    /**
     * 单次运行的记录器：每个事件先落 SQLite（回放/断线续传的事实来源），再推送订阅连接；
     * 推送失败只摘除订阅，任务继续执行。write 步的流式增量攒一小段再发，降低前端重渲染频率。
     */
    private class RunRecorder {
        private final RunSession session;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final StringBuilder streamBuf = new StringBuilder();
        private int seq = 0;

        RunRecorder(RunSession session) {
            this.session = session;
        }

        @SuppressWarnings("unchecked")
        void send(String event, Object data) {
            synchronized (session) {
                if (data instanceof Map) {
                    ((Map<String, Object>) data).putIfAbsent("seq", seq);
                }
                runStore.saveEvent(session.runId(), seq, event, data);
                seq++;
                SseEmitter emitter = session.emitter();
                if (emitter != null) {
                    try {
                        emitter.send(SseEmitter.event().name(event).data(data));
                    } catch (Exception ex) {
                        session.detachEmitter(emitter);
                    }
                }
            }
        }

        void streamDelta(int stepIndex, String piece) {
            streamBuf.append(piece);
            if (streamBuf.length() >= STREAM_FLUSH_CHARS) {
                flushStream(stepIndex);
            }
        }

        void flushStream(int stepIndex) {
            if (streamBuf.length() == 0) {
                return;
            }
            String delta = streamBuf.toString();
            streamBuf.setLength(0);
            send("result-delta", map("index", stepIndex, "delta", delta));
        }

        /** 收尾只生效一次：正常 done / 异常 error / 取消 cancelled */
        void finish(String status, String summary, String output) {
            if (finished.compareAndSet(false, true)) {
                runStore.finishRun(session.runId(), status, summary, output);
                session.markFinished();
                evictFinishedSessions();
            }
        }
    }
}
