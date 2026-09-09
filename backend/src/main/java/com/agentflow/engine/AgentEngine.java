package com.agentflow.engine;

import com.agentflow.llm.LlmClient;
import com.agentflow.model.PlanStep;
import com.agentflow.model.ToolCall;
import com.agentflow.tool.StockTool;
import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
import com.agentflow.tool.WeatherTool;
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

    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final RunStore runStore;
    private final String reportDept;
    private final String agentMode;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, RunSession> sessions = new ConcurrentHashMap<>();

    public AgentEngine(ToolRegistry toolRegistry, LlmClient llmClient, RunStore runStore,
                       @Value("${agentflow.report.department:中台研发部}") String reportDept,
                       @Value("${agentflow.agent.mode:plan}") String agentMode) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.runStore = runStore;
        this.reportDept = reportDept == null || reportDept.isBlank() ? "中台研发部" : reportDept.trim();
        this.agentMode = agentMode == null ? "plan" : agentMode.trim().toLowerCase();
    }

    /** ReAct 自主模式：配置开启且 LLM 可用时生效，失败自动回退线性规划 */
    private boolean reactEnabled() {
        return "react".equals(agentMode) && llmClient.isEnabled();
    }

    /** 提交任务：立即后台执行（不依赖前端订阅），事件全部落库，随时可 attach 查看 */
    public String start(String command, List<Map<String, String>> history, String mode) {
        String taskId = UUID.randomUUID().toString();
        long runId = runStore.createRun(taskId, command);
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
        String histBlock = historyBlock(session.history());
        try {
            /* 阶段一+二：意图分析与任务规划（LLM 模式一次调用同时完成；confirm 模式推送计划等待放行；ReAct 跳过预规划） */
            recorder.send("phase", map("name", "understand", "state", "active"));
            recorder.send("status", map("text", "正在解析指令并规划任务…", "cls", "is-running"));
            List<PlanStep> steps = null;
            int total = 0;
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

            /* 阶段三：逐步执行（同 group 的连续 tool 步并行；ReAct 模式逐轮决策） */
            recorder.send("phase", map("name", "execute", "state", "active"));
            Map<String, String> toolResults = new LinkedHashMap<>();
            String writeOutput = null;
            if (steps == null) {
                int stepIdx = 0;
                boolean wrote = false;
                while (stepIdx < MAX_REACT_STEPS) {
                    checkCancelled(session);
                    ReactDecision d = reactDecide(command, histBlock, toolResults);
                    if (d == null || "finish".equals(d.action())) {
                        break;
                    }
                    boolean isWrite = "write".equals(d.action()) || d.tool() == null;
                    String title = d.note() == null || d.note().isBlank()
                            ? (isWrite ? "生成最终交付内容" : "调用工具取数") : d.note();
                    PlanStep s = isWrite ? PlanStep.write(title) : PlanStep.tool(title, d.tool());
                    recorder.send("step", map("index", stepIdx, "total", MAX_REACT_STEPS,
                            "kind", s.kind(), "tag", s.tag(), "title", s.title()));
                    String out = executeStep(recorder, session, command, intent, histBlock, s, stepIdx, toolResults, writeOutput);
                    if (isWrite) {
                        writeOutput = out;
                        wrote = true;
                        break;
                    }
                    stepIdx++;
                }
                if (!wrote && writeOutput == null) {
                    // 决策循环未产出交付物：兜底补一个 write 步
                    recorder.send("step", map("index", stepIdx, "total", MAX_REACT_STEPS,
                            "kind", "write", "tag", "内容生成", "title", "汇总生成最终交付内容"));
                    writeOutput = executeStep(recorder, session, command, intent, histBlock,
                            PlanStep.write("汇总生成最终交付内容"), stepIdx, toolResults, writeOutput);
                }
                total = Math.max(stepIdx + 1, 1);
            } else {
                for (int i = 0; i < steps.size(); ) {
                    checkCancelled(session);
                    int j = parallelEnd(steps, i);
                    if (j - i > 1) {
                        executeParallel(recorder, session, command, intent, histBlock, steps, i, j, toolResults);
                    } else {
                        writeOutput = executeStep(recorder, session, command, intent, histBlock, steps.get(i), i, toolResults, writeOutput);
                    }
                    i = j;
                }
            }
            recorder.send("phase", map("name", "execute", "state", "done"));

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
        } catch (CancelledException ex) {
            recorder.send("status", map("text", "已手动停止", "cls", "is-done"));
            recorder.finish("cancelled", "已手动停止", "");
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

    /** 启发式意图抽取：城市/股票实体表 + 关键词类别 */
    private Intent heuristicIntent(String command) {
        List<String> entities = new ArrayList<>();
        for (String c : WeatherTool.findCities(command)) {
            entities.add("城市:" + c);
        }
        String stockName = StockTool.findStock(command);
        if (stockName != null) {
            entities.add("股票:" + stockName);
        }
        List<String> categories = new ArrayList<>();
        if (command.contains("天气")) categories.add("天气查询");
        if (stockName != null || command.contains("股价") || command.contains("股票")) categories.add("行情查询");
        if (command.contains("高铁") || command.contains("交通") || command.contains("怎么去") || command.contains("机票")) categories.add("交通方案");
        if (command.contains("美食") || command.contains("吃") || command.contains("景点") || command.contains("推荐")) categories.add("本地推荐");
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
                                 List<PlanStep> steps, int from, int to, Map<String, String> toolResults) {
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
                    executeStep(recorder, session, command, intent, histBlock, steps.get(k), k, partial, null);
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

    /** 问 LLM 决定下一步动作；失败返回 null（由调用方收敛循环） */
    private ReactDecision reactDecide(String command, String histBlock, Map<String, String> toolResults) {
        try {
            String system = String.format(REACT_SYSTEM_TEMPLATE, toolRegistry.describeForPrompt());
            String content = llmClient.chatJson(system,
                    "任务目标：" + command + "\n已获得的工具结果：\n" + toolSummary(toolResults) + histBlock
                            + "\n请决定下一步动作。");
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

    /** 通用启发式拆解：任何指令都能得到合理计划 */
    private List<PlanStep> heuristicPlan(String command) {
        List<PlanStep> steps = new ArrayList<>();

        // 0. GitLab 工作报告：拉提交 → 归纳 → 成稿，专线处理
        String reportKind = detectReportKind(command);
        if (reportKind != null) {
            boolean weekly = "weekly".equals(reportKind);
            String dayArg = weekly ? "week"
                    : (command.contains("昨天") || command.contains("昨日")) ? "yesterday" : "today";
            steps.add(PlanStep.tool("拉取我的 GitLab 提交记录（" + (weekly ? "本周" : dayArg.equals("yesterday") ? "昨天" : "今天") + "）",
                    new ToolCall("gitlab.query", Map.of("type", "mine", "day", dayArg))));
            steps.add(PlanStep.think("归纳提交记录 · 提炼工作主线"));
            steps.add(PlanStep.write("生成工作" + (weekly ? "周报" : "日报")));
            return steps;
        }

        // 1. 实体驱动的工具步（真实数据 API）
        String city = WeatherTool.findCity(command);
        boolean wantsWeather = command.contains("天气");
        if (city != null && (wantsWeather || command.contains("气温") || command.contains("下雨"))) {
            steps.add(PlanStep.tool("查询" + city + "今日天气", new ToolCall("weather.query", Map.of("city", city))));
        }
        String stock = StockTool.findStock(command);
        if (stock != null) {
            steps.add(PlanStep.tool("查询" + stock + "实时行情",
                    new ToolCall("stock.query", Map.of("stock", stock))));
        }
        boolean wantsTransit = command.contains("高铁") || command.contains("交通") || command.contains("怎么去")
                || command.contains("机票") || command.contains("火车");
        List<String> cities = WeatherTool.findCities(command);
        if (wantsTransit && !cities.isEmpty()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("from", cities.get(0));
            if (cities.size() > 1) args.put("to", cities.get(1));
            steps.add(PlanStep.tool("规划" + String.join("→", cities) + "交通方案",
                    new ToolCall("transit.query", args)));
        }
        boolean wantsPoi = command.contains("美食") || command.contains("小吃") || command.contains("景点")
                || (command.contains("推荐") && city != null);
        if (wantsPoi) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (city != null) args.put("city", city);
            args.put("keyword", command.contains("景点") ? "景点" : "美食");
            steps.add(PlanStep.tool("收集" + (city == null ? "目的地" : city) + "美食/景点推荐",
                    new ToolCall("poi.recommend", args)));
        }

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
    private String detectReportKind(String command) {
        String lower = command.toLowerCase();
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

    /* ================= 步骤执行 ================= */

    /** 返回 write 步生成的内容（供汇总复用） */
    private String executeStep(RunRecorder recorder, RunSession session, String command, Intent intent, String histBlock,
                               PlanStep s, int index, Map<String, String> toolResults,
                               String writeOutput) {
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
            // 摘要 + 具体数据一并交给后续 LLM 推理/汇总，避免模型只凭一句话编造细节
            String detail = tr.list() == null || tr.list().isEmpty()
                    ? String.valueOf(tr.result() == null ? Map.of() : tr.result())
                    : String.join("；", tr.list());
            String summary = (tr.summary() == null ? "" : tr.summary()) + "｜" + truncate(detail, 800);
            // 同名工具可能被规划多次，key 带步骤序号避免相互覆盖
            toolResults.put(s.tool().name() + "#" + index, summary);

            recorder.send("result", map("index", index, "resultType", tr.resultType(),
                    "result", tr.result() == null ? Map.of() : tr.result(),
                    "list", tr.list() == null ? List.of() : tr.list()));
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
            LocalDate refDate = reportRefDate(command);
            String system = reportKind != null ? reportWriteSystem(reportKind, refDate) : WRITE_SYSTEM;
            String userPrompt = "用户指令：" + command + "\n意图：" + intent.summary()
                    + "\n已获得的工具结果：\n" + toolSummary(toolResults) + histBlock
                    + "\n请生成最终成品内容。";

            String content = null;
            if (llmClient.isEnabled()) {
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
                        ? templateReport(toolResults, "weekly".equals(reportKind), refDate)
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

    /** 报告基准日：指令提到昨天/昨日（晨报场景）用昨天，否则今天 */
    private static LocalDate reportRefDate(String command) {
        return command != null && (command.contains("昨天") || command.contains("昨日"))
                ? LocalDate.now().minusDays(1)
                : LocalDate.now();
    }

    /** 报告生成 prompt：给出行结构示例 + 日期星期对照表 + 硬性约束，按「日期（星期）+ 当日工作主线」逐行排点 */
    private String reportWriteSystem(String kind, LocalDate ref) {
        boolean weekly = "weekly".equals(kind);
        String kindZh = weekly ? "周报" : "日报";
        String planZh = weekly ? "下周" : "明日";
        String scope = weekly
                ? ref.minusDays(6) + "（" + weekdayZh(ref.minusDays(6)) + "）至 " + ref + "（" + weekdayZh(ref) + "）"
                : ref + "（" + weekdayZh(ref) + "）";
        // 模型不会算星期，直接给对照表
        StringBuilder lookup = new StringBuilder();
        for (LocalDate d = weekly ? ref.minusDays(6) : ref; !d.isAfter(ref); d = d.plusDays(1)) {
            if (lookup.length() > 0) {
                lookup.append("，");
            }
            lookup.append(String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth())).append("=").append(weekdayZh(d));
        }
        String sample = weekly
                ? "【" + reportDept + "】个人效能周报\n"
                  + "2026-09-07（周一）数据权限功能开发（权限配置组件开发，UI 调整），运行态组件 UI 调整\n"
                  + "2026-09-08（周二）通用记录操作中心开发（通用操作记录查询组件开发），数据权限联调\n"
                  + "【下周计划】\n1. 跟进数据权限合入后的联调验证"
                : "【" + reportDept + "】个人效能日报\n"
                  + "2026-09-08（周二）数据权限功能开发（权限配置组件开发，UI 调整），运行态组件 UI 调整\n"
                  + "【明日计划】\n1. 跟进数据权限合入后的联调验证";
        return "你是 AgentFlow 的工作报告生成模块。请基于提供的 Git 提交记录，严格按下面的行结构输出工作报告。\n"
                + "输出结构示例（示例中的日期与内容仅示意格式，必须替换为提交记录中的真实工作，禁止照抄示例文字）：\n"
                + sample + "\n"
                + "硬性要求：\n"
                + "1. 第一行固定为「【" + reportDept + "】个人效能" + kindZh + "」，一字不改\n"
                + "2. 之后时间窗内每个有提交的日期独占一行，行首日期必须带星期括注，如 2026-09-08（周二），星期从对照表取，日期按先后排列\n"
                + "3. 同一天的多个提交必须归纳合并为几条工作主线（可带中文圆括号补充细节），主线之间用中文逗号分隔，禁止逐条罗列提交；Merge/分支合并类提交若无实质内容并入相关主线，不必单列\n"
                + "4. 最后是「【" + planZh + "计划】」单独一行，其下 1~3 条计划，每条独占一行并以“1. ”“2. ”编号（基于已有工作合理延伸，没有依据时只写“1. 待补充”）\n"
                + "5. 全文必须是纯文本：禁止任何 Markdown 标记（**、#、-、*、` 等），不要“提交人”行、不要总结段、不要任何解释\n"
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

    /** 模拟模式的报告模板：按天归组真实提交行，逐行排点，不做推断 */
    private String templateReport(Map<String, String> toolResults, boolean weekly, LocalDate ref) {
        // 从工具结果里抽出 "MM-dd HH:mm · 标题" 形式的提交行，按日期归组
        Map<LocalDate, List<String>> byDay = new TreeMap<>();
        for (String v : toolResults.values()) {
            int bar = v.indexOf('｜');
            String detail = bar >= 0 ? v.substring(bar + 1) : v;
            for (String line : detail.split("；")) {
                String t = line.trim();
                LocalDate d = matchDate(t, ref, weekly);
                if (d != null) {
                    int dot = t.indexOf('·');
                    String title = dot >= 0 ? t.substring(dot + 1).trim() : t;
                    byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(title);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(reportDept).append("】个人效能").append(weekly ? "周报" : "日报").append("\n");
        if (byDay.isEmpty()) {
            sb.append(ref).append("（").append(weekdayZh(ref)).append("）暂无提交记录\n");
        } else {
            for (Map.Entry<LocalDate, List<String>> e : byDay.entrySet()) {
                sb.append(e.getKey()).append("（").append(weekdayZh(e.getKey())).append("）")
                        .append(String.join("，", e.getValue())).append("\n");
            }
        }
        sb.append("【").append(weekly ? "下周" : "明日").append("计划】待补充\n");
        sb.append("\n（模拟模式：由模板基于真实 GitLab 提交记录生成；配置 DEEPSEEK_API_KEY 后将由 LLM 归纳生成完整报告）");
        return sb.toString();
    }

    /** 把提交行开头的 MM-dd 匹配到报告时间窗内的具体日期；非提交行返回 null */
    private static LocalDate matchDate(String line, LocalDate today, boolean weekly) {
        if (line.length() < 5 || line.charAt(2) != '-') {
            return null;
        }
        String mmdd = line.substring(0, 5);
        int span = weekly ? 6 : 0;
        for (int i = 0; i <= span; i++) {
            LocalDate d = today.minusDays(i);
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
