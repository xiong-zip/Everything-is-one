package com.agentflow.alarm;

import com.agentflow.engine.AgentEngine;
import com.agentflow.notify.NotifyService;
import com.agentflow.signoz.TraceAnalysisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 告警自动值守：收到监控告警后<b>不等任何人开口</b>，自动派 Agent 排查并推送结论。
 *
 * <p>这是把已有的四块能力（链路分析、变更关联、K8s 排查、推送通道）串成一个闭环——
 * 运行机制上没有任何新东西，新增的只是「谁来触发」：此前只有 cron 和用户输入两个触发源，
 * 现在多了监控平台的 webhook。
 *
 * <p>三个必须自己兜住的地方：
 * <ul>
 *   <li><b>去重</b>——告警风暴时同一条告警可能在几分钟内上报几十次（监控平台普遍如此），
 *       每次触发都跑一遍完整排查既烧 token 又刷屏，所以同一 {@code dedupKey} 在窗口内只排查一次；</li>
 *   <li><b>异步</b>——排查要跑几十秒到几分钟，而 webhook 发送方通常只等几秒就判超时重发，
 *       同步等待会把「重发」变成「重复排查」，因此受理即返回、排查在后台线程做完再推送；</li>
 *   <li><b>已恢复的告警不排查</b>——恢复通知没有根因可查，只留痕。</li>
 * </ul>
 *
 * <p>管控开关：总开关（{@code agentflow.alarm.enabled}）、共享令牌（{@code agentflow.alarm.token}，
 * 因为这是个能触发真实排查和推送的公网可及端点）、去重窗口、排查超时、指令模板。
 */
@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
    /** 推送正文里留给排查结论的字符数；再长交给 NotifyService 按字节截断 */
    private static final int OUTPUT_CHARS_IN_PUSH = 1200;

    private final AgentEngine engine;
    private final NotifyService notify;
    private final AlarmStore store;
    private final TraceAnalysisStore analysisStore;

    private final ThreadPoolExecutor workers;
    private final boolean enabled;
    private final String token;
    private final long timeoutMs;
    private final int dedupSeconds;
    private final String commandTemplate;

    public AlarmService(AgentEngine engine, NotifyService notify, AlarmStore store,
                        TraceAnalysisStore analysisStore,
                        @Value("${agentflow.alarm.enabled:true}") boolean enabled,
                        @Value("${agentflow.alarm.token:}") String token,
                        @Value("${agentflow.alarm.timeout-ms:180000}") long timeoutMs,
                        @Value("${agentflow.alarm.dedup-seconds:600}") int dedupSeconds,
                        @Value("${agentflow.alarm.command:}") String commandTemplate,
                        @Value("${agentflow.alarm.max-concurrent:2}") int maxConcurrent) {
        this.engine = engine;
        this.notify = notify;
        this.store = store;
        this.analysisStore = analysisStore;
        this.enabled = enabled;
        this.token = token == null ? "" : token.trim();
        this.timeoutMs = timeoutMs <= 0 ? 180_000L : timeoutMs;
        this.dedupSeconds = dedupSeconds < 0 ? 0 : dedupSeconds;
        this.commandTemplate = commandTemplate == null ? "" : commandTemplate.trim();

        int workers = Math.max(1, Math.min(maxConcurrent, 8));
        // 有界队列：告警风暴叠加去重失效时，宁可明确拒绝（并落库为失败），也不要无限堆任务
        this.workers = new ThreadPoolExecutor(workers, workers, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "alarm-investigator");
            t.setDaemon(true);
            return t;
        });
        if (this.token.isEmpty()) {
            log.warn("告警值守未配置共享令牌（agentflow.alarm.token），任何能访问该端口的请求都可触发排查与推送");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 令牌校验：未配置令牌时不校验（本地自用场景），配置了就必须匹配 */
    public boolean authorized(String provided) {
        return token.isEmpty() || token.equals(provided == null ? "" : provided.trim());
    }

    /**
     * 受理一条告警：去重判断 → 落库 → 后台排查。
     * 返回受理结果（不含排查结论，结论稍后推送并写入记录）。
     */
    public Map<String, Object> intake(AlarmEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) {
            out.put("accepted", false);
            out.put("action", "disabled");
            out.put("reason", "告警值守已关闭（agentflow.alarm.enabled / AGENTFLOW_ALARM_ENABLED）");
            return out;
        }
        if (event == null) {
            out.put("accepted", false);
            out.put("action", "invalid");
            out.put("reason", "载荷为空或无法解析");
            return out;
        }
        if (event.resolved()) {
            store.insert(event, "skipped");
            log.info("收到已恢复的告警，不排查：{}", event.title());
            out.put("accepted", true);
            out.put("action", "skipped");
            out.put("reason", "已恢复的告警无需排查根因");
            return out;
        }
        int recent = store.recentCount(event.dedupKey(), dedupSeconds);
        if (recent > 0) {
            store.insert(event, "skipped");
            log.info("告警在 {} 秒内已上报 {} 次，本次不重复排查：{}", dedupSeconds, recent, event.title());
            out.put("accepted", true);
            out.put("action", "deduped");
            out.put("reason", "同一告警在 " + dedupSeconds + " 秒内已上报 " + recent + " 次，已在排查或不重复排查");
            out.put("recentCount", recent);
            return out;
        }

        String command = buildCommand(event);
        long id = store.insert(event, "running");
        out.put("accepted", true);
        out.put("action", "investigating");
        out.put("recordId", id);
        out.put("command", command);

        try {
            workers.execute(() -> investigate(id, event, command));
        } catch (RejectedExecutionException ex) {
            store.finish(id, "error", "", "排查队列已满，本次未执行", "", false, 0);
            log.warn("告警排查队列已满，丢弃本次排查：{}", event.title());
            out.put("action", "rejected");
            out.put("reason", "排查队列已满，请稍后重试");
            out.put("accepted", false);
        }
        return out;
    }

    /** 后台排查 + 推送：整段包在 try 里，任何异常都不能让值守线程静默死掉 */
    private void investigate(long recordId, AlarmEvent event, String command) {
        long start = System.currentTimeMillis();
        log.info("告警自动排查开始（记录 {}）：{}", recordId, command);
        String runTaskId = "";
        String summary = "";
        String output = "";
        String state = "error";
        boolean pushed = false;
        try {
            Map<String, String> run = engine.executeHeadless(command, timeoutMs);
            runTaskId = run.getOrDefault("taskId", "");
            summary = run.getOrDefault("summary", "");
            output = run.getOrDefault("output", "");
            state = "done".equals(run.getOrDefault("status", "error")) ? "done" : "error";
            if ("done".equals(state)) {
                pushed = notify.sendAlert(pushTitle(event), composePush(event, summary, output, runTaskId));
            } else {
                log.warn("告警排查未正常完成（记录 {}，状态 {}）", recordId, state);
            }
        } catch (Throwable t) {
            // 捕获 Throwable：这里和 NotifyService 是同一类边界，Error 逃出去会让记录永远停在「排查中」
            log.error("告警排查异常（记录 {}）：{}", recordId, t.toString());
            summary = summary.isEmpty() ? "排查过程中出错：" + t.getMessage() : summary;
        }
        store.finish(recordId, state, runTaskId, summary, output, pushed, System.currentTimeMillis() - start);
        log.info("告警自动排查结束（记录 {}，状态 {}，推送 {}，耗时 {} ms）",
                recordId, state, pushed ? "成功" : "未推送/失败", System.currentTimeMillis() - start);
    }

    /**
     * 生成排查指令。配了模板就用模板，否则按「有链路 → 有服务 → 都没有」三档给默认指令：
     * 有 trace ID 时链路分析能直接定位到失败点，是最有价值的入口。
     */
    String buildCommand(AlarmEvent event) {
        if (!commandTemplate.isEmpty()) {
            return render(commandTemplate, event);
        }
        return render(defaultCommand(event.hasTraceId(), event.hasService()), event);
    }

    /** 默认指令模板：按告警里能拿到的最强线索选择排查入口 */
    static String defaultCommand(boolean hasTraceId, boolean hasService) {
        if (hasTraceId) {
            return "分析链路 {traceId}，再用 gitlab.changes 关联这次故障前后的代码变更，"
                    + "给出根因、失败传播链与处置建议";
        }
        if (hasService) {
            return "排查服务 {service} 的异常：查看它的 Pod 状态与所在命名空间最近的 Warning 事件，"
                    + "并关联该服务最近的代码变更";
        }
        return "排查这次监控告警并给出处置建议：{title}｜{message}";
    }

    /** 模板占位符替换；未提供的字段填空串，避免把 null 塞进指令里 */
    static String render(String template, AlarmEvent event) {
        return template
                .replace("{traceId}", event.traceId())
                .replace("{service}", event.service())
                .replace("{alertName}", event.alertName())
                .replace("{severity}", event.severity())
                .replace("{message}", event.message())
                .replace("{title}", event.title())
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String pushTitle(AlarmEvent event) {
        return "【AgentFlow 告警排查】" + event.title();
    }

    /**
     * 推送正文：把「是什么告警」放在最前面，结论其次，完整过程只留开头——
     * 因为群消息会被按字节截断，尾部先没，关键信息必须在前。
     */
    String composePush(AlarmEvent event, String summary, String output, String runTaskId) {
        StringBuilder sb = new StringBuilder();
        sb.append("级别：").append(event.severityOrInfo()).append("　来源：").append(event.source()).append("\n");
        if (event.hasService()) {
            sb.append("服务：").append(event.service()).append("\n");
        }
        if (event.hasTraceId()) {
            sb.append("链路：").append(event.traceId()).append("\n");
        }
        String regression = regressionNote(event.traceId());
        if (!regression.isEmpty()) {
            sb.append("\n").append(regression).append("\n");
        }
        sb.append("\n排查结论：\n").append(summary == null || summary.isBlank() ? "（无摘要）" : summary);
        String body = output == null ? "" : output.trim();
        if (!body.isEmpty()) {
            sb.append("\n\n").append(body.length() <= OUTPUT_CHARS_IN_PUSH
                    ? body : body.substring(0, OUTPUT_CHARS_IN_PUSH) + "…（已截断）");
        }
        if (runTaskId != null && !runTaskId.isBlank()) {
            sb.append("\n\n（完整过程见 AgentFlow 工作台 · 告警值守，任务 ").append(runTaskId, 0, 8).append("）");
        }
        return sb.toString();
    }

    /**
     * 指纹回归判断：本次排查出的故障指纹此前是否已经出现过。
     * 出现过就说明同一类错误在「修好」之后又回来了，这比单次告警本身更值得注意。
     */
    String regressionNote(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return "";
        }
        try {
            TraceAnalysisStore.AnalysisRecord rec = analysisStore.findByTraceId(traceId);
            if (rec == null || rec.signature() == null || rec.signature().isBlank()) {
                return "";
            }
            int count = analysisStore.countBySignature(rec.signature());
            if (count <= 1) {
                return "";
            }
            List<String> others = analysisStore.traceIdsBySignature(rec.signature(), traceId, 3);
            StringBuilder sb = new StringBuilder();
            sb.append("⚠️ 疑似回归：故障指纹「").append(rec.signature())
                    .append("」历史累计出现 ").append(count).append(" 次");
            if (!others.isEmpty()) {
                sb.append("，此前链路：").append(String.join("、", others));
            }
            return sb.toString();
        } catch (Exception ex) {
            log.warn("指纹回归判断失败：{}", ex.getMessage());
            return "";
        }
    }

    /** 面板状态：开关、令牌是否启用、去重窗口、当前排查指令默认形态、记录汇总 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("tokenRequired", !token.isEmpty());
        m.put("timeoutMs", timeoutMs);
        m.put("dedupSeconds", dedupSeconds);
        m.put("maxConcurrent", workers.getMaximumPoolSize());
        m.put("running", workers.getActiveCount());
        m.put("queued", workers.getQueue().size());
        m.put("notifyConfigured", notify.isConfigured());
        m.put("commandTemplate", commandTemplate);
        m.put("stats", store.stats());
        return m;
    }

    public Map<String, Object> records(int limit, int offset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records", store.list(limit, offset));
        m.put("total", store.count());
        return m;
    }

    public boolean deleteRecord(long id) {
        return store.delete(id);
    }

    public int clearRecords() {
        return store.clear();
    }

    /** 用给定字段模拟一条告警，走完整链路（受理 → 排查 → 推送），用于上线前验证配置 */
    public Map<String, Object> simulate(String alertName, String service, String traceId, String severity) {
        AlarmEvent event = new AlarmEvent("simulate",
                alertName == null || alertName.isBlank() ? "（模拟告警）" : alertName.trim(),
                severity == null || severity.isBlank() ? "critical" : severity.trim(),
                "firing",
                service == null ? "" : service.trim(),
                AlarmParser.firstTraceId(traceId == null ? "" : traceId),
                "这是一条由工作台发起的模拟告警，用于验证自动排查与推送链路是否通畅。",
                "simulate|" + System.nanoTime(), "{}");
        return intake(event);
    }
}
