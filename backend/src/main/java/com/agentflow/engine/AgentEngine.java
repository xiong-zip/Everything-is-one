package com.agentflow.engine;

import com.agentflow.llm.LlmClient;
import com.agentflow.model.PlanStep;
import com.agentflow.model.ToolCall;
import com.agentflow.tool.StockTool;
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

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final String INTENT_SYSTEM =
            "你是 AgentFlow 的意图分析模块。分析用户指令，输出一个 JSON 对象：" +
            "{\"summary\": \"一句话概括用户想要什么（30 字以内）\", " +
            "\"entities\": [\"识别到的关键实体，如 城市:北京、股票:贵州茅台、日期:明天\"]}。" +
            "只输出 JSON，不要输出任何多余文字。";

    private static final String PLAN_SYSTEM_TEMPLATE =
            "你是 AgentFlow 的任务规划器。请把用户指令拆解成 2~5 个有序、可执行的子任务，输出 JSON 对象：" +
            "{\"steps\": [{\"kind\": \"tool|think|write\", \"tag\": \"简短类型标签\", \"title\": \"一句话子任务描述\", " +
            "\"tool\": {\"name\": \"工具名\", \"args\": {参数对象}}}]}\n" +
            "规则：\n" +
            "- kind：tool=调用工具取数，think=分析推理，write=生成最终交付内容；tool 字段仅 kind=tool 时给出，否则为 null\n" +
            "- 最后一步一般是 write；需要数据支撑时先安排 tool 步，再 think，最后 write\n" +
            "- args 必须按工具参数说明填写结构化 JSON 对象\n" +
            "- 指令引用对话历史中的内容时，规划应基于历史产出继续加工而不是重新取数\n" +
            "可用工具：\n%s" +
            "只输出 JSON，不要输出任何多余文字或代码块。";

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

    private static final String SUMMARY_SYSTEM =
            "你是 AgentFlow 的汇总模块。用 30 字以内的一句话概括本次任务交付了什么，直接输出这句话，不要任何多余文字。";

    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final RunStore runStore;
    private final String reportDept;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, PendingTask> tasks = new ConcurrentHashMap<>();

    /** 已提交待消费的任务：指令 + 多轮历史 + 持久化运行 id */
    private record PendingTask(String command, List<Map<String, String>> history, long runId) {
    }

    public AgentEngine(ToolRegistry toolRegistry, LlmClient llmClient, RunStore runStore,
                       @Value("${agentflow.report.department:中台研发部}") String reportDept) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
        this.runStore = runStore;
        this.reportDept = reportDept == null || reportDept.isBlank() ? "中台研发部" : reportDept.trim();
    }

    public String start(String command, List<Map<String, String>> history) {
        String taskId = UUID.randomUUID().toString();
        long runId = runStore.createRun(taskId, command);
        tasks.put(taskId, new PendingTask(command, sanitizeHistory(history), runId));
        return taskId;
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

    public SseEmitter stream(String taskId) {
        PendingTask task = tasks.remove(taskId);
        if (task == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        RunRecorder recorder = new RunRecorder(emitter, task.runId());
        emitter.onTimeout(() -> {
            recorder.finish("interrupted", "", "");
            emitter.complete();
        });
        executor.submit(() -> orchestrate(recorder, task));
        return emitter;
    }

    /* ================= 编排主流程 ================= */

    private void orchestrate(RunRecorder recorder, PendingTask task) {
        SseEmitter emitter = recorder.emitter();
        String command = task.command();
        String histBlock = historyBlock(task.history());
        try {
            /* 阶段一：意图分析（真实抽取，非演出） */
            recorder.send("status", map("text", "正在解析指令意图…", "cls", "is-running"));
            recorder.send("phase", map("name", "understand", "state", "active"));
            Intent intent = analyzeIntent(command, histBlock);
            recorder.send("intent", map("summary", intent.summary(), "entities", intent.entities()));
            sleep(180);
            recorder.send("phase", map("name", "understand", "state", "done"));

            /* 阶段二：任务规划（LLM 优先，启发式兜底） */
            List<PlanStep> steps = plan(command, intent, histBlock);
            int total = steps.size();
            recorder.send("status", map("text", "已拆解为 " + total + " 个子任务，开始执行…", "cls", "is-running"));
            recorder.send("phase", map("name", "plan", "state", "active"));
            recorder.send("plan", map("total", total));
            for (int i = 0; i < total; i++) {
                PlanStep s = steps.get(i);
                recorder.send("step", map("index", i, "total", total,
                        "kind", s.kind(), "tag", s.tag(), "title", s.title()));
                sleep(150);
            }
            recorder.send("phase", map("name", "plan", "state", "done"));

            /* 阶段三：逐步执行 */
            recorder.send("phase", map("name", "execute", "state", "active"));
            Map<String, String> toolResults = new LinkedHashMap<>();
            String writeOutput = null;
            for (int i = 0; i < total; i++) {
                writeOutput = executeStep(recorder, command, intent, histBlock, steps.get(i), i, toolResults, writeOutput);
            }
            recorder.send("phase", map("name", "execute", "state", "done"));

            /* 阶段四：结果汇总 */
            recorder.send("phase", map("name", "merge", "state", "active"));
            recorder.send("status", map("text", "正在汇总各任务结果…", "cls", "is-running"));
            sleep(200);
            String[] finalOut = mergeFinal(command, histBlock, toolResults, writeOutput);
            recorder.send("done", map(
                    "summary", finalOut[0],
                    "output", finalOut[1],
                    "meta", buildMeta(total, toolResults)));

            recorder.send("phase", map("name", "merge", "state", "done"));
            recorder.send("status", map("text", "✓ 执行完成", "cls", "is-done"));
            recorder.finish("done", finalOut[0], finalOut[1]);
            emitter.complete();
        } catch (IOException ex) {
            recorder.finish("interrupted", "", "");
            emitter.complete();
        } catch (Exception ex) {
            log.error("任务执行异常", ex);
            recorder.finish("error", "任务执行异常", "");
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
            }
        }
    }

    /* ================= 意图分析 ================= */

    private record Intent(String summary, List<String> entities) {
    }

    private Intent analyzeIntent(String command, String histBlock) {
        if (llmClient.isEnabled()) {
            try {
                String content = llmClient.chatJson(INTENT_SYSTEM, "用户指令：" + command + histBlock);
                JsonNode node = readJsonObject(content);
                String summary = node.path("summary").asText("");
                if (!summary.isBlank()) {
                    List<String> entities = new ArrayList<>();
                    node.path("entities").forEach(e -> {
                        String t = e.asText().trim();
                        if (!t.isEmpty()) entities.add(t);
                    });
                    return new Intent(summary, entities);
                }
            } catch (Exception ex) {
                log.warn("LLM 意图分析失败，使用启发式: {}", ex.getMessage());
            }
        }
        return heuristicIntent(command);
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

    private List<PlanStep> plan(String command, Intent intent, String histBlock) {
        if (llmClient.isEnabled()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    List<PlanStep> steps = llmPlan(command, histBlock);
                    if (!steps.isEmpty()) {
                        return steps;
                    }
                } catch (Exception ex) {
                    log.warn("LLM 规划第 {} 次失败: {}", attempt + 1, ex.getMessage());
                }
            }
        }
        return heuristicPlan(command);
    }

    private List<PlanStep> llmPlan(String command, String histBlock) {
        String system = String.format(PLAN_SYSTEM_TEMPLATE, toolRegistry.describeForPrompt());
        String content = llmClient.chatJson(system, "用户指令：" + command + histBlock);
        JsonNode node = readJsonObject(content);
        JsonNode arr = node.has("steps") && node.path("steps").isArray()
                ? node.path("steps")
                : fallbackArray(content);
        List<PlanStep> steps = new ArrayList<>();
        if (arr == null) {
            return steps;
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
            steps.add(new PlanStep(kind, tag, title, tool, lines));
        }
        return steps;
    }

    /** 通用启发式拆解：任何指令都能得到合理计划 */
    private List<PlanStep> heuristicPlan(String command) {
        List<PlanStep> steps = new ArrayList<>();

        // 0. GitLab 工作报告：拉提交 → 归纳 → 成稿，专线处理
        String reportKind = detectReportKind(command);
        if (reportKind != null) {
            boolean weekly = "weekly".equals(reportKind);
            steps.add(PlanStep.tool("拉取我的 GitLab 提交记录（" + (weekly ? "本周" : "今天") + "）",
                    new ToolCall("gitlab.query", Map.of("type", "mine", "day", weekly ? "week" : "today"))));
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
    private String executeStep(RunRecorder recorder, String command, Intent intent, String histBlock,
                               PlanStep s, int index, Map<String, String> toolResults,
                               String writeOutput) throws IOException {
        recorder.send("step-state", map("index", index, "state", "running"));
        recorder.send("status", map("text", "正在执行 · " + s.title(), "cls", "is-running"));

        if ("tool".equals(s.kind()) && s.tool() != null) {
            recorder.send("tool", map("index", index, "name", s.tool().name(), "args", s.tool().args()));
            sleep(220);

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
            sleep(200);
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
                sleep(160);
            }
            recorder.send("step-state", map("index", index, "state", "done"));
            return writeOutput;
        }

        if ("write".equals(s.kind())) {
            String reportKind = detectReportKind(command);
            String system = reportKind != null ? reportWriteSystem(reportKind) : WRITE_SYSTEM;
            String userPrompt = "用户指令：" + command + "\n意图：" + intent.summary()
                    + "\n已获得的工具结果：\n" + toolSummary(toolResults) + histBlock
                    + "\n请生成最终成品内容。";

            String content = null;
            if (llmClient.isEnabled()) {
                try {
                    // 流式生成：增量片段实时推送，最终以完整 result 事件为准
                    content = llmClient.chatStream(system, userPrompt, piece -> recorder.streamDelta(index, piece));
                } catch (Exception ex) {
                    log.warn("LLM 流式生成失败，尝试非流式重试: {}", ex.getMessage());
                } finally {
                    recorder.flushStream(index);
                }
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
                        ? templateReport(toolResults, "weekly".equals(reportKind))
                        : templateWrite(command, intent, toolResults);
            }
            if (reportKind != null) {
                // 报告要求纯文本，兜底清掉模型偶尔混入的 Markdown 标记
                content = stripMarkdown(content);
            }
            recorder.send("result", map("index", index, "resultType", "copy",
                    "result", map("versions", List.of(map("tag", llmGenerated ? "AI 生成" : "模拟模式 · 模板生成", "text", content))),
                    "list", List.of()));
            sleep(200);
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

    /** 报告生成 prompt：给出行结构示例 + 日期星期对照表 + 硬性约束，按「日期（星期）+ 当日工作主线」逐行排点 */
    private String reportWriteSystem(String kind) {
        boolean weekly = "weekly".equals(kind);
        LocalDate today = LocalDate.now();
        String kindZh = weekly ? "周报" : "日报";
        String planZh = weekly ? "下周" : "明日";
        String scope = weekly
                ? today.minusDays(6) + "（" + weekdayZh(today.minusDays(6)) + "）至 " + today + "（" + weekdayZh(today) + "）"
                : today + "（" + weekdayZh(today) + "）";
        // 模型不会算星期，直接给对照表
        StringBuilder lookup = new StringBuilder();
        for (LocalDate d = weekly ? today.minusDays(6) : today; !d.isAfter(today); d = d.plusDays(1)) {
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
    private String templateReport(Map<String, String> toolResults, boolean weekly) {
        LocalDate today = LocalDate.now();
        // 从工具结果里抽出 "MM-dd HH:mm · 标题" 形式的提交行，按日期归组
        Map<LocalDate, List<String>> byDay = new TreeMap<>();
        for (String v : toolResults.values()) {
            int bar = v.indexOf('｜');
            String detail = bar >= 0 ? v.substring(bar + 1) : v;
            for (String line : detail.split("；")) {
                String t = line.trim();
                LocalDate d = matchDate(t, today, weekly);
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
            sb.append(today).append("（").append(weekdayZh(today)).append("）暂无提交记录\n");
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
        // write 步已产出成品时直接采用原文，避免汇总改写破坏交付物格式；只补一句话总结
        if (writeOutput != null && !writeOutput.isBlank()) {
            String summary = null;
            if (llmClient.isEnabled()) {
                try {
                    String s = llmClient.chat(SUMMARY_SYSTEM,
                            "用户指令：" + command + "\n交付物内容：\n" + truncate(writeOutput, 1200));
                    List<String> lines = splitLines(s);
                    if (!lines.isEmpty()) {
                        summary = truncate(lines.get(0), 40);
                    }
                } catch (Exception ex) {
                    log.warn("LLM 总结生成失败: {}", ex.getMessage());
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
     * 单次运行的记录器：每个 SSE 事件边发送边落库（回放用）；
     * write 步的流式增量攒一小段再发，降低前端重渲染频率。
     */
    private class RunRecorder {
        private final SseEmitter emitter;
        private final long runId;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final StringBuilder streamBuf = new StringBuilder();
        private int seq = 0;

        RunRecorder(SseEmitter emitter, long runId) {
            this.emitter = emitter;
            this.runId = runId;
        }

        SseEmitter emitter() {
            return emitter;
        }

        void send(String event, Object data) throws IOException {
            emitter.send(SseEmitter.event().name(event).data(data));
            runStore.saveEvent(runId, seq++, event, data);
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
            try {
                send("result-delta", map("index", stepIndex, "delta", delta));
            } catch (Exception ex) {
                log.warn("流式增量推送失败: {}", ex.getMessage());
            }
        }

        /** 收尾只生效一次：正常 done / 异常 error / 超时 interrupted */
        void finish(String status, String summary, String output) {
            if (finished.compareAndSet(false, true)) {
                runStore.finishRun(runId, status, summary, output);
            }
        }
    }
}
