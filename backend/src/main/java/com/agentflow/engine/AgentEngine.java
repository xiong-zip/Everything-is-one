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
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 通用 Agent 编排引擎：任意自然语言指令 → 意图分析 → 动态规划 → 逐步执行 → 汇总。
 * 有 LLM 时规划/推理/生成走 LLM；无 LLM 时走启发式拆解 + 真实数据 API + 明确标注的模拟内容。
 */
@Service
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);
    private static final long EMITTER_TIMEOUT_MS = 180_000L;
    private static final int MAX_REASON_LINES = 6;

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
            "可用工具：\n%s" +
            "只输出 JSON，不要输出任何多余文字或代码块。";

    private static final String THINK_SYSTEM =
            "你是 AgentFlow 智能体中负责分析与规划的子模块。请用中文简洁地输出对当前任务的推理过程：" +
            "先提炼关键信息，再说明思路，最后给出结论。每行一句，共 3~6 行。" +
            "不要输出标题、编号、代码块或多余解释。";

    private static final String WRITE_SYSTEM =
            "你是 AgentFlow 智能体中的内容生成模块，负责生成最终交付物。请直接输出成品内容（无需任何解释），" +
            "并严格遵守任务要求。";

    private static final String FINAL_SYSTEM =
            "你是 AgentFlow 智能体，负责把各子任务的执行结果汇总成最终交付物。" +
            "请按以下格式输出：第一行是一句话总结（30 字以内），空一行后输出完整成品。";

    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> tasks = new ConcurrentHashMap<>();

    public AgentEngine(ToolRegistry toolRegistry, LlmClient llmClient) {
        this.toolRegistry = toolRegistry;
        this.llmClient = llmClient;
    }

    public String start(String command) {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, command);
        return taskId;
    }

    public SseEmitter stream(String taskId) {
        String command = tasks.remove(taskId);
        if (command == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        executor.submit(() -> orchestrate(emitter, command));
        return emitter;
    }

    /* ================= 编排主流程 ================= */

    private void orchestrate(SseEmitter emitter, String command) {
        try {
            /* 阶段一：意图分析（真实抽取，非演出） */
            send(emitter, "status", map("text", "正在解析指令意图…", "cls", "is-running"));
            send(emitter, "phase", map("name", "understand", "state", "active"));
            Intent intent = analyzeIntent(command);
            send(emitter, "intent", map("summary", intent.summary(), "entities", intent.entities()));
            sleep(180);
            send(emitter, "phase", map("name", "understand", "state", "done"));

            /* 阶段二：任务规划（LLM 优先，启发式兜底） */
            List<PlanStep> steps = plan(command, intent);
            int total = steps.size();
            send(emitter, "status", map("text", "已拆解为 " + total + " 个子任务，开始执行…", "cls", "is-running"));
            send(emitter, "phase", map("name", "plan", "state", "active"));
            send(emitter, "plan", map("total", total));
            for (int i = 0; i < total; i++) {
                PlanStep s = steps.get(i);
                send(emitter, "step", map("index", i, "total", total,
                        "kind", s.kind(), "tag", s.tag(), "title", s.title()));
                sleep(150);
            }
            send(emitter, "phase", map("name", "plan", "state", "done"));

            /* 阶段三：逐步执行 */
            send(emitter, "phase", map("name", "execute", "state", "active"));
            Map<String, String> toolResults = new LinkedHashMap<>();
            String writeOutput = null;
            for (int i = 0; i < total; i++) {
                writeOutput = executeStep(emitter, command, intent, steps.get(i), i, toolResults, writeOutput);
            }
            send(emitter, "phase", map("name", "execute", "state", "done"));

            /* 阶段四：结果汇总 */
            send(emitter, "phase", map("name", "merge", "state", "active"));
            send(emitter, "status", map("text", "正在汇总各任务结果…", "cls", "is-running"));
            sleep(200);
            String[] finalOut = mergeFinal(command, toolResults, writeOutput);
            send(emitter, "done", map(
                    "summary", finalOut[0],
                    "output", finalOut[1],
                    "meta", buildMeta(total, toolResults)));

            send(emitter, "phase", map("name", "merge", "state", "done"));
            send(emitter, "status", map("text", "✓ 执行完成", "cls", "is-done"));
            emitter.complete();
        } catch (IOException ex) {
            emitter.complete();
        } catch (Exception ex) {
            log.error("任务执行异常", ex);
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
            }
        }
    }

    /* ================= 意图分析 ================= */

    private record Intent(String summary, List<String> entities) {
    }

    private Intent analyzeIntent(String command) {
        if (llmClient.isEnabled()) {
            try {
                String content = llmClient.chatJson(INTENT_SYSTEM, "用户指令：" + command);
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

    private List<PlanStep> plan(String command, Intent intent) {
        if (llmClient.isEnabled()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    List<PlanStep> steps = llmPlan(command);
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

    private List<PlanStep> llmPlan(String command) {
        String system = String.format(PLAN_SYSTEM_TEMPLATE, toolRegistry.describeForPrompt());
        String content = llmClient.chatJson(system, "用户指令：" + command);
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

        // 2. 通用分析步
        steps.add(PlanStep.think("提炼任务要点 · 确定产出方向"));
        // 3. 通用生成步
        steps.add(PlanStep.write("生成「" + truncate(command, 16) + "」交付内容"));
        return steps;
    }

    /* ================= 步骤执行 ================= */

    /** 返回 write 步生成的内容（供汇总复用） */
    private String executeStep(SseEmitter emitter, String command, Intent intent, PlanStep s, int index,
                               Map<String, String> toolResults, String writeOutput) throws IOException {
        send(emitter, "step-state", map("index", index, "state", "running"));
        send(emitter, "status", map("text", "正在执行 · " + s.title(), "cls", "is-running"));

        if ("tool".equals(s.kind()) && s.tool() != null) {
            send(emitter, "tool", map("index", index, "name", s.tool().name(), "args", s.tool().args()));
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
            toolResults.put(s.tool().name(), tr.summary() == null ? "" : tr.summary());

            send(emitter, "result", map("index", index, "resultType", tr.resultType(),
                    "result", tr.result() == null ? Map.of() : tr.result(),
                    "list", tr.list() == null ? List.of() : tr.list()));
            sleep(200);
            send(emitter, "step-state", map("index", index, "state", "done"));
            return writeOutput;
        }

        if ("think".equals(s.kind())) {
            List<String> lines = s.lines();
            if (llmClient.isEnabled()) {
                try {
                    String reasoning = llmClient.reason(THINK_SYSTEM,
                            "用户指令：" + command + "\n子任务：" + s.title()
                                    + "\n意图：" + intent.summary()
                                    + "\n已获得的工具结果：\n" + toolSummary(toolResults));
                    List<String> generated = splitLines(reasoning);
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
                send(emitter, "reason", map("index", index, "line", line));
                sleep(160);
            }
            send(emitter, "step-state", map("index", index, "state", "done"));
            return writeOutput;
        }

        if ("write".equals(s.kind())) {
            String content = null;
            if (llmClient.isEnabled()) {
                try {
                    content = llmClient.chat(WRITE_SYSTEM,
                            "用户指令：" + command + "\n意图：" + intent.summary()
                                    + "\n已获得的工具结果：\n" + toolSummary(toolResults)
                                    + "\n请生成最终成品内容。");
                } catch (Exception ex) {
                    log.warn("LLM 生成失败，使用模板内容: {}", ex.getMessage());
                }
            }
            if (content == null || content.isBlank()) {
                content = templateWrite(command, intent, toolResults);
            }
            send(emitter, "result", map("index", index, "resultType", "copy",
                    "result", map("versions", List.of(map("tag", llmClient.isEnabled() ? "AI 生成" : "模拟模式 · 模板生成", "text", content))),
                    "list", List.of()));
            sleep(200);
            send(emitter, "step-state", map("index", index, "state", "done"));
            return content;
        }

        /* 其余 kind：通用占位 */
        send(emitter, "result", map("index", index, "resultType", "json",
                "result", Map.of("note", "已跳过不支持的步骤类型：" + s.kind()), "list", List.of()));
        send(emitter, "step-state", map("index", index, "state", "done"));
        return writeOutput;
    }

    /** 模拟模式的要点分析：基于指令与已获数据的确定性推导 */
    private List<String> heuristicThinkLines(String command, Intent intent, Map<String, String> toolResults) {
        List<String> lines = new ArrayList<>();
        lines.add("任务目标：" + truncate(command, 30));
        if (!toolResults.isEmpty()) {
            lines.add("已获取数据：" + truncate(String.join("；", toolResults.values()), 60));
        }
        if (!intent.entities().isEmpty()) {
            lines.add("关键要素：" + String.join("、", intent.entities()));
        }
        lines.add("产出方向：围绕任务目标整合以上信息，形成结构化交付内容");
        lines.add("无需人工介入，可直接进入生成阶段");
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

    /* ================= 汇总 ================= */

    private String[] mergeFinal(String command, Map<String, String> toolResults, String writeOutput) {
        if (llmClient.isEnabled()) {
            try {
                String content = llmClient.chat(FINAL_SYSTEM,
                        "用户指令：" + command + "\n各子任务执行结果：\n" + toolSummary(toolResults)
                                + "\n生成步内容：\n" + (writeOutput == null ? "（无）" : writeOutput)
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
        String output = writeOutput != null ? writeOutput : templateWrite(command, heuristicIntent(command), toolResults);
        return new String[]{summary, output};
    }

    private List<String> buildMeta(int total, Map<String, String> toolResults) {
        List<String> meta = new ArrayList<>();
        meta.add("子任务 " + total + " 个");
        meta.add("工具调用 " + toolResults.size() + " 次");
        meta.add(llmClient.isEnabled() ? "LLM 动态规划" : "模拟模式 · 启发式规划");
        return meta;
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /* ================= SSE 发送 ================= */

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private static Map<String, Object> map(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
