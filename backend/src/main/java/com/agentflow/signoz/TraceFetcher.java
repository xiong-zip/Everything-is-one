package com.agentflow.signoz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 链路抓取：查 trace 详情，查不到自动扩时间窗；失败链路补查 ERROR/WARN 日志。
 *
 * 时间窗降级是必需的：默认 24h 只覆盖当天，两天前的链路会直接"查不到"，
 * 而 SigNoz 的提示里其实带着 trace 真实所处的时间段，这里把它透出去而不是丢掉。
 */
@Component
public class TraceFetcher {

    private static final Logger log = LoggerFactory.getLogger(TraceFetcher.class);

    private final SigNozMcpClient client;
    private final String defaultRange;
    private final String fallbackRange;
    private final int logLimit;

    public TraceFetcher(SigNozMcpClient client,
                        @Value("${agentflow.signoz.time-range:24h}") String defaultRange,
                        @Value("${agentflow.signoz.fallback-range:7d}") String fallbackRange,
                        @Value("${agentflow.signoz.log-limit:10}") int logLimit) {
        this.client = client;
        this.defaultRange = blankTo(defaultRange, "24h");
        this.fallbackRange = blankTo(fallbackRange, "7d");
        this.logLimit = logLimit <= 0 ? 10 : logLimit;
    }

    /** 抓取结果：rangeUsed 是最终命中的时间窗，notice 是服务端提示（如真实时间段） */
    public record Fetched(List<TraceSpan> spans, List<TraceDigest.LogLine> logs,
                          String rangeUsed, String notice) {

        public boolean found() {
            return !spans.isEmpty();
        }

        public boolean failed() {
            return TraceDigest.errorOrigin(spans) != null;
        }
    }

    /**
     * 抓取链路。requestedRange 为空时用默认窗口，抓不到自动退到更宽的窗口。
     * 失败链路会自动补查 ERROR/WARN 日志（成功链路不查，避免无谓开销）。
     */
    public Fetched fetch(String traceId, String requestedRange) {
        String first = blankTo(requestedRange, defaultRange);
        Candidate c = tryFetch(traceId, first);
        if (!c.spans().isEmpty()) {
            return withLogs(c);
        }
        if (!first.equals(fallbackRange)) {
            Candidate wide = tryFetch(traceId, fallbackRange);
            if (!wide.spans().isEmpty()) {
                return withLogs(wide);
            }
            // 两个窗口都空：优先透出更宽窗口的服务端提示（含 trace 真实时间段）
            String notice = wide.notice() != null ? wide.notice() : c.notice();
            return new Fetched(List.of(), List.of(), fallbackRange, notice);
        }
        return new Fetched(List.of(), List.of(), first, c.notice());
    }

    private Fetched withLogs(Candidate c) {
        List<TraceDigest.LogLine> logs = List.of();
        if (TraceDigest.errorOrigin(c.spans()) != null) {
            try {
                String payload = client.callTool("signoz_search_logs", Map.of(
                        "query", "trace_id = '" + c.traceId() + "' AND severity_text IN ('ERROR', 'WARN')",
                        "limit", String.valueOf(logLimit),
                        "timeRange", c.range()));
                logs = TraceDigest.parseLogs(payload);
            } catch (Exception ex) {
                log.warn("补查 trace {} 错误日志失败：{}", c.traceId(), ex.getMessage());
            }
        }
        return new Fetched(c.spans(), logs, c.range(), c.notice());
    }

    private record Candidate(String traceId, String range, List<TraceSpan> spans, String notice) {
    }

    private Candidate tryFetch(String traceId, String range) {
        String payload = client.callTool("signoz_get_trace_details", Map.of(
                "traceId", traceId,
                "timeRange", range,
                "includeSpans", "true"));
        TraceDigest.ParsedSpans parsed = TraceDigest.parseSpans(payload);
        return new Candidate(traceId, range, parsed.spans(), parsed.notice());
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
