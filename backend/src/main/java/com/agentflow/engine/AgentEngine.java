package com.agentflow.engine;

import com.agentflow.model.AgentStep;
import com.agentflow.model.FinalData;
import com.agentflow.model.Scenario;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AgentEngine {

    private static final long EMITTER_TIMEOUT_MS = 120_000L;

    private final ScenarioRegistry registry;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AgentEngine(ScenarioRegistry registry) {
        this.registry = registry;
    }

    public SseEmitter run(String command) {
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
                executeStep(emitter, steps.get(i), i);
            }
            send(emitter, "phase", map("name", "execute", "state", "done"));

            /* 阶段四：结果汇总 */
            send(emitter, "phase", map("name", "merge", "state", "active"));
            send(emitter, "status", map("text", "正在汇总各任务结果…", "cls", "is-running"));
            sleep(650);

            FinalData f = scenario.finalData();
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

    private void executeStep(SseEmitter emitter, AgentStep s, int index) throws IOException {
        send(emitter, "step-state", map("index", index, "state", "running"));
        send(emitter, "status", map("text", "正在执行 · " + s.getTitle(), "cls", "is-running"));

        if (s.getTool() != null) {
            send(emitter, "tool", map("index", index, "name", s.getTool().name(), "args", s.getTool().args()));
            sleep(420);
        }

        if (s.getLines() != null) {
            for (String line : s.getLines()) {
                send(emitter, "reason", map("index", index, "line", line));
                sleep(240);
            }
        } else if ("write".equals(s.getKind())) {
            sleep(320);
        }

        sleep(Math.max(180, s.getDur() - 350));

        send(emitter, "result", map(
                "index", index,
                "resultType", s.getResultType(),
                "result", s.getResult() == null ? Map.of() : s.getResult(),
                "list", s.getList() == null ? List.of() : s.getList()));

        sleep(300);
        send(emitter, "step-state", map("index", index, "state", "done"));
    }

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
