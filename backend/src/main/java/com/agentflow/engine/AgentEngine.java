package com.agentflow.engine;

import com.agentflow.llm.LlmClient;
import com.agentflow.model.AgentStep;
import com.agentflow.model.FinalData;
import com.agentflow.model.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
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

    private final ScenarioRegistry registry;
    private final LlmClient llmClient;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> tasks = new ConcurrentHashMap<>();

    public AgentEngine(ScenarioRegistry registry, LlmClient llmClient) {
        this.registry = registry;
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
            Scenario scenario = registry.find(command);
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
            for (int i = 0; i < steps.size(); i++) {
                executeStep(emitter, scenario, command, steps.get(i), i);
            }
            send(emitter, "phase", map("name", "execute", "state", "done"));

            /* 阶段四：结果汇总 */
            send(emitter, "phase", map("name", "merge", "state", "active"));
            send(emitter, "status", map("text", "正在汇总各任务结果…", "cls", "is-running"));
            sleep(400);

            FinalData f = scenario.finalData();
            if (llmClient.isEnabled()) {
                try {
                    String content = llmClient.chat(FINAL_SYSTEM, buildFinalPrompt(scenario, command));
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

    private void executeStep(SseEmitter emitter, Scenario scenario, String userCommand, AgentStep s, int index) throws IOException {
        send(emitter, "step-state", map("index", index, "state", "running"));
        send(emitter, "status", map("text", "正在执行 · " + s.getTitle(), "cls", "is-running"));

        if (s.getTool() != null) {
            send(emitter, "tool", map("index", index, "name", s.getTool().name(), "args", s.getTool().args()));
            sleep(420);
        }

        if (s.getLines() != null) {
            /* 智能分析：优先用 LLM 真实推理，失败则回退模拟 */
            List<String> lines = s.getLines();
            if (llmClient.isEnabled()) {
                try {
                    String reasoning = llmClient.reason(THINK_SYSTEM, buildThinkPrompt(scenario, s, userCommand));
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
                    String content = llmClient.chat(WRITE_SYSTEM, buildWritePrompt(scenario, userCommand));
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

        /* 工具结果：模拟数据 */
        sleep(Math.max(180, s.getDur() - 350));
        send(emitter, "result", map(
                "index", index,
                "resultType", s.getResultType(),
                "result", s.getResult() == null ? Map.of() : s.getResult(),
                "list", s.getList() == null ? List.of() : s.getList()));
        sleep(300);
        send(emitter, "step-state", map("index", index, "state", "done"));
    }

    /* ---------- LLM Prompt 构造 ---------- */

    private String toolSummary(Scenario scenario) {
        StringBuilder sb = new StringBuilder();
        for (AgentStep s : scenario.steps()) {
            if (s.getTool() != null && s.getResult() != null) {
                sb.append("- ").append(s.getTool().name()).append(" → ").append(s.getResult()).append("\n");
            }
        }
        return sb.length() == 0 ? "（无工具结果）" : sb.toString();
    }

    private String buildThinkPrompt(Scenario scenario, AgentStep step, String userCommand) {
        return "用户指令：" + userCommand + "\n"
                + "当前子任务：" + step.getTitle() + "\n"
                + "已获得的工具结果：\n" + toolSummary(scenario)
                + "\n请输出推理过程。";
    }

    private String buildWritePrompt(Scenario scenario, String userCommand) {
        return "用户指令：" + userCommand + "\n"
                + "已获得的工具结果：\n" + toolSummary(scenario)
                + "\n请生成最终成品内容。";
    }

    private String buildFinalPrompt(Scenario scenario, String userCommand) {
        return "用户指令：" + userCommand + "\n"
                + "各子任务执行结果：\n" + toolSummary(scenario)
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
