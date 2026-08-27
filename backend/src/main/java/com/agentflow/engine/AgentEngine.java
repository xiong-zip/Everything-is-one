package com.agentflow.engine;

import com.agentflow.llm.LlmClient;
import com.agentflow.model.AgentStep;
import com.agentflow.model.FinalData;
import com.agentflow.model.Scenario;
import com.agentflow.model.ToolCall;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
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

@Service
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);
    private static final long EMITTER_TIMEOUT_MS = 180_000L;
    private static final int MAX_REASON_LINES = 6;

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

    private static final String PLAN_SYSTEM =
            "你是 AgentFlow 的任务规划器。请把用户指令拆解成 2~4 个有序、可执行的子任务。\n" +
            "每个子任务必须是 JSON 对象，字段：\n" +
            "- \"kind\": \"tool\" | \"think\" | \"write\"（tool=调用工具取数，think=分析推理，write=生成最终内容）\n" +
            "- \"tag\": 简短类型标签（如 工具调用、智能分析、内容生成）\n" +
            "- \"title\": 一句话描述该子任务\n" +
            "- \"tool\": 仅当 kind 为 tool 时给出 {\"name\": 工具名, \"args\": \"参数说明\"}，否则为 null\n" +
            "可用工具：weather.query（查天气）、stock.query（查股价行情）、transit.query（查交通方式）、" +
            "poi.recommend（推荐当地美食）、llm.generate（AI 生成内容）\n" +
            "请只输出一个 JSON 数组，不要输出任何多余文字、解释或代码块。";

    private final ScenarioRegistry registry;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> tasks = new ConcurrentHashMap<>();

    public AgentEngine(ScenarioRegistry registry, ToolRegistry toolRegistry, LlmClient llmClient) {
        this.registry = registry;
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

    private void orchestrate(SseEmitter emitter, String command) {
        try {
            Scenario scenario = resolveScenario(command);
            List<AgentStep> steps = scenario.steps();
            int total = steps.size();

            /* 阶段一：意图分析 */
            send(emitter, "status", map("text", "正在解析指令意图…", "cls", "is-running"));
            send(emitter, "phase", map("name", "understand", "state", "active"));
            sleep(650);
            send(emitter, "phase", map("name", "understand", "state", "done"));

            /* 阶段二：任务规划 */
            send(emitter, "status", map("text", "正在拆解任务，生成 " + total + " 个子任务执行计划…", "cls", "is-running"));
            send(emitter, "phase", map("name", "plan", "state", "active"));
            send(emitter, "plan", map("total", total));
            for (int i = 0; i < steps.size(); i++) {
                AgentStep s = steps.get(i);
                send(emitter, "step", map(
                        "index", i, "total", total,
                        "kind", s.getKind(), "tag", s.getTag(), "title", s.getTitle()));
                sleep(260);
            }
            send(emitter, "phase", map("name", "plan", "state", "done"));

            /* 阶段三：逐步执行 */
            send(emitter, "phase", map("name", "execute", "state", "active"));
            Map<String, String> toolResults = new LinkedHashMap<>();
            for (int i = 0; i < steps.size(); i++) {
                executeStep(emitter, command, steps.get(i), i, toolResults);
            }
            send(emitter, "phase", map("name", "execute", "state", "done"));

            /* 阶段四：结果汇总 */
            send(emitter, "phase", map("name", "merge", "state", "active"));
            send(emitter, "status", map("text", "正在汇总各任务结果…", "cls", "is-running"));
            sleep(400);

            FinalData f = scenario.finalData();
            if (llmClient.isEnabled()) {
                try {
                    String content = llmClient.chat(FINAL_SYSTEM, buildFinalPrompt(command, toolResults));
                    f = parseFinal(content, f);
                } catch (Exception ex) {
                    log.warn("LLM 汇总失败，使用模拟数据: {}", ex.getMessage());
                }
            }

            send(emitter, "done", map(
                    "summary", f.summary(),
                    "output", f.output(),
                    "meta", f.meta()));

            send(emitter, "phase", map("name", "merge", "state", "done"));
            send(emitter, "status", map("text", "✓ 执行完成", "cls", "is-done"));
            emitter.complete();
        } catch (IOException ex) {
            emitter.complete();
        } catch (Exception ex) {
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
            }
        }
    }

    private void executeStep(SseEmitter emitter, String userCommand, AgentStep s, int index,
                             Map<String, String> toolResults) throws IOException {
        send(emitter, "step-state", map("index", index, "state", "running"));
        send(emitter, "status", map("text", "正在执行 · " + s.getTitle(), "cls", "is-running"));

        if (s.getTool() != null) {
            send(emitter, "tool", map("index", index, "name", s.getTool().name(), "args", s.getTool().args()));
            sleep(420);

            ToolResult tr = null;
            try {
                tr = toolRegistry.execute(s.getTool().name(), userCommand);
            } catch (Exception ex) {
                log.warn("工具 {} 执行失败，使用模拟数据: {}", s.getTool().name(), ex.getMessage());
            }

            Map<String, Object> result = s.getResult() == null ? Map.of() : s.getResult();
            List<String> list = s.getList();
            String resultType = s.getResultType();
            String summary = "（模拟数据）";
            if (tr != null) {
                result = tr.result() == null ? Map.of() : tr.result();
                list = tr.list();
                resultType = tr.resultType();
                summary = tr.summary() == null ? "" : tr.summary();
            }
            toolResults.put(s.getTool().name(), summary);

            sleep(Math.max(180, s.getDur() - 420));
            send(emitter, "result", map("index", index, "resultType", resultType, "result", result, "list", list));
            sleep(300);
            send(emitter, "step-state", map("index", index, "state", "done"));
            return;
        }

        if (s.getLines() != null) {
            /* 智能分析：优先用 LLM 真实推理，失败则回退模拟 */
            List<String> lines = s.getLines();
            if (llmClient.isEnabled()) {
                try {
                    String reasoning = llmClient.reason(THINK_SYSTEM, buildThinkPrompt(s, userCommand, toolResults));
                    List<String> generated = splitLines(reasoning);
                    if (!generated.isEmpty()) {
                        lines = generated;
                    }
                } catch (Exception ex) {
                    log.warn("LLM 推理失败，使用模拟数据: {}", ex.getMessage());
                }
            }
            for (String line : lines) {
                send(emitter, "reason", map("index", index, "line", line));
                sleep(220);
            }
            send(emitter, "step-state", map("index", index, "state", "done"));
            return;
        }

        if ("write".equals(s.getKind())) {
            /* 内容生成：优先用 LLM 真实生成，失败则回退模拟 */
            Map<String, Object> result = s.getResult();
            if (llmClient.isEnabled()) {
                try {
                    String content = llmClient.chat(WRITE_SYSTEM, buildWritePrompt(userCommand, toolResults));
                    result = map("versions", List.of(map("tag", "AI 生成", "text", content)));
                } catch (Exception ex) {
                    log.warn("LLM 生成失败，使用模拟数据: {}", ex.getMessage());
                }
            }
            sleep(320);
            send(emitter, "result", map("index", index, "resultType", "copy", "result", result, "list", List.of()));
            sleep(300);
            send(emitter, "step-state", map("index", index, "state", "done"));
            return;
        }

        /* 其余步骤：通用占位结果 */
        sleep(Math.max(180, s.getDur() - 350));
        send(emitter, "result", map(
                "index", index,
                "resultType", s.getResultType(),
                "result", s.getResult() == null ? Map.of() : s.getResult(),
                "list", s.getList() == null ? List.of() : s.getList()));
        sleep(300);
        send(emitter, "step-state", map("index", index, "state", "done"));
    }

    private Scenario resolveScenario(String command) {
        Scenario scenario = registry.tryFind(command);
        if (scenario != null) {
            return scenario;
        }
        if (llmClient.isEnabled()) {
            try {
                return buildDynamicScenario(command);
            } catch (Exception ex) {
                log.warn("LLM 动态规划失败，回退默认场景: {}", ex.getMessage());
            }
        }
        return registry.fallback();
    }

    private Scenario buildDynamicScenario(String command) {
        String content = llmClient.chat(PLAN_SYSTEM, "用户指令：" + command);
        List<AgentStep> steps = parsePlan(content);
        if (steps.isEmpty()) {
            throw new IllegalStateException("未解析到有效计划");
        }
        FinalData finalData = new FinalData(
                "已完成 " + steps.size() + " 个子任务：LLM 动态规划 → 逐步执行 → AI 汇总。",
                "",
                List.of("LLM 动态规划", "真实工具调用", "AI 汇总"));
        return new Scenario("dynamic", command, "动态任务", steps, finalData);
    }

    private List<AgentStep> parsePlan(String content) {
        List<AgentStep> steps = new ArrayList<>();
        try {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return steps;
            }
            JsonNode arr = mapper.readTree(content.substring(start, end + 1));
            if (!arr.isArray()) {
                return steps;
            }
            for (JsonNode n : arr) {
                String kind = n.path("kind").asText("tool");
                String tag = n.path("tag").asText(kind);
                String title = n.path("title").asText("子任务");
                ToolCall tool = null;
                JsonNode t = n.get("tool");
                if (t != null && t.isObject()) {
                    String name = t.path("name").asText("");
                    if (!name.isBlank()) {
                        tool = new ToolCall(name, t.path("args").asText(""));
                    }
                }
                List<String> lines = "think".equals(kind) ? List.of() : null;
                steps.add(new AgentStep(kind, tag, title, tool, lines, null, null, null, 1300));
            }
        } catch (Exception ex) {
            log.warn("解析动态计划失败: {}", ex.getMessage());
        }
        return steps;
    }

    /* ---------- LLM Prompt 构造 ---------- */

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

    private String buildThinkPrompt(AgentStep step, String userCommand, Map<String, String> toolResults) {
        return "用户指令：" + userCommand + "\n"
                + "当前子任务：" + step.getTitle() + "\n"
                + "已获得的工具结果：\n" + toolSummary(toolResults)
                + "\n请输出推理过程。";
    }

    private String buildWritePrompt(String userCommand, Map<String, String> toolResults) {
        return "用户指令：" + userCommand + "\n"
                + "已获得的工具结果：\n" + toolSummary(toolResults)
                + "\n请生成最终成品内容。";
    }

    private String buildFinalPrompt(String userCommand, Map<String, String> toolResults) {
        return "用户指令：" + userCommand + "\n"
                + "各子任务执行结果：\n" + toolSummary(toolResults)
                + "\n请按格式汇总输出。";
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

    private static FinalData parseFinal(String content, FinalData fallback) {
        List<String> cleaned = new ArrayList<>();
        for (String l : content.split("\n")) {
            if (!l.isBlank()) {
                cleaned.add(l.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return fallback;
        }
        String summary = cleaned.get(0);
        String output = cleaned.size() > 1
                ? String.join("\n", cleaned.subList(1, cleaned.size()))
                : summary;
        return new FinalData(summary, output, fallback.meta());
    }

    /* ---------- SSE 发送 ---------- */

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
