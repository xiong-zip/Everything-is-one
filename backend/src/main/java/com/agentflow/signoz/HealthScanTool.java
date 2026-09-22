package com.agentflow.signoz;

import com.agentflow.alarm.AlarmStore;
import com.agentflow.k8s.KuboardClient;
import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主动健康巡检工具（signoz.health，只读）：不等告警上门，主动扫一遍系统的风险面——
 *
 * 一次调用聚合三类信号并按风险分级输出：
 * 1. SigNoz 服务指标（signoz_list_services 一次拿全：错误率 / P99 / 调用量 / 4xx）；
 * 2. K8s 异常 Pod（经 Kuboard 全命名空间扫描：CrashLoop / Pending / 未就绪 / 高重启，可选）；
 * 3. 近窗口内的告警值守记录（AlarmStore，说明「哪些风险已经被值守处理过」）。
 *
 * 适合两类用法：对话里直接说「巡检 / 体检 / 健康检查」；
 * 或在定时任务面板加一条「对全部服务做健康巡检，生成晨间风险预告」——到点自动跑并推送。
 */
@Component
public class HealthScanTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(HealthScanTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单个服务的巡检指标（errorPct / p99Ms 已换算为百分比与毫秒） */
    record SvcStat(String name, long calls, long errors, double errorPct, double p99Ms,
                   double avgMs, double fourxxPct, long num4xx) {
    }

    /** 一条风险项：level 2=高 1=中；source 标明来自哪类信号 */
    record Risk(int level, String source, String service, String item, String detail) {

        static String levelText(int level) {
            return level >= 2 ? "高" : "中";
        }
    }

    private final SigNozMcpClient client;
    private final KuboardClient kuboard;
    private final AlarmStore alarmStore;
    private final String defaultCluster;

    public HealthScanTool(SigNozMcpClient client, KuboardClient kuboard, AlarmStore alarmStore,
                          @Value("${agentflow.kuboard.cluster:dev}") String defaultCluster) {
        this.client = client;
        this.kuboard = kuboard;
        this.alarmStore = alarmStore;
        this.defaultCluster = defaultCluster == null || defaultCluster.isBlank() ? "dev" : defaultCluster.trim();
    }

    @Override
    public String name() {
        return "signoz.health";
    }

    @Override
    public String description() {
        if (!client.isConfigured()) {
            return "主动健康巡检（当前未配置 SigNoz MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL 后重启）";
        }
        return "主动健康巡检（只读）：一次性扫描全部服务的错误率/P99/调用量、K8s 异常 Pod（CrashLoop/Pending/未就绪/高重启）"
                + "与近期告警值守记录，输出按风险分级（高/中）的风险清单，高危在前。"
                + "用户说「巡检」「体检」「健康检查」「有没有风险」时用本工具；"
                + "也适合作为定时任务指令（如「对全部服务做健康巡检，生成晨间风险预告」）在每天上班前推送。只读。";
    }

    @Override
    public String argsHint() {
        return "{\"timeRange\": \"统计窗口，默认 6h（可选 1h|6h|24h|7d）\", "
                + "\"errorRatePct\": \"高风险错误率阈值（百分比，默认 1，低于告警线才能发现早期风险）\", "
                + "\"p99Ms\": \"P99 延迟阈值（毫秒，默认 3000）\", "
                + "\"minCalls\": \"参与判定的最小调用量（默认 50，低于它的服务不给风险结论，避免小样本误报）\", "
                + "\"cluster\": \"K8s 集群名（默认 " + defaultCluster + "，未配置 Kuboard 时跳过 K8s 部分）\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!client.isConfigured()) {
            return ToolResult.note("未配置 SigNoz MCP 地址：请在 .env 设置 SIGNOZ_MCP_URL 后重启服务");
        }
        Map<String, Object> a = args == null ? Map.of() : args;
        String timeRange = str(a.get("timeRange"), "6h");
        // 默认阈值 1%：巡检的定位是「发现还没触发告警（常见 5%）的早期风险」，阈值应低于告警线
        double errorRatePct = parseDouble(a.get("errorRatePct"), 1.0);
        double p99Ms = parseDouble(a.get("p99Ms"), 3000.0);
        long minCalls = (long) parseDouble(a.get("minCalls"), 50.0);
        String cluster = str(a.get("cluster"), defaultCluster);

        // 1. 服务指标：一次 MCP 调用拿全量服务统计
        List<SvcStat> services;
        try {
            String payload = client.callTool("signoz_list_services", Map.of(
                    "limit", "200", "timeRange", timeRange));
            services = parseServices(payload);
        } catch (Exception ex) {
            return ToolResult.note("巡检失败：查询 SigNoz 服务指标出错 - " + ex.getMessage());
        }
        if (services.isEmpty()) {
            return ToolResult.note("窗口 " + timeRange + " 内 SigNoz 没有返回任何服务数据（可能窗口内无流量，或采集未接入）");
        }

        List<Risk> risks = new ArrayList<>();
        for (SvcStat s : services) {
            int level = classify(s, errorRatePct, p99Ms, minCalls);
            if (level > 0) {
                risks.add(new Risk(level, "服务指标", s.name(), s.name(), serviceDetail(s)));
            }
        }

        // 2. K8s 异常 Pod（未配置 Kuboard 就跳过，不阻断巡检）
        String k8sNote = null;
        if (kuboard.isConfigured()) {
            try {
                risks.addAll(k8sRisks(cluster));
            } catch (Exception ex) {
                k8sNote = "K8s 部分扫描失败：" + ex.getMessage() + "（服务指标结论不受影响）";
                log.warn("健康巡检扫描 K8s 异常失败：{}", ex.getMessage());
            }
        }
        risks.sort(Comparator.comparingInt(Risk::level).reversed()
                .thenComparing((Risk r) -> r.service() == null ? "" : r.service(), Comparator.reverseOrder()));

        // 3. 近窗口告警值守记录：说明哪些风险已被自动值守处理过
        int alarmCount = recentAlarms(timeRange);

        long high = risks.stream().filter(r -> r.level() >= 2).count();
        long mid = risks.stream().filter(r -> r.level() == 1).count();

        List<String> lines = new ArrayList<>();
        lines.add("巡检窗口 " + timeRange + " · 服务 " + services.size() + " 个 · 高风险 " + high
                + " · 中风险 " + mid + (kuboard.isConfigured() ? " · K8s 已扫描" : " · K8s 未配置已跳过")
                + (alarmCount > 0 ? " · 窗口内告警值守 " + alarmCount + " 条" : ""));
        // 口径说明前置：错误率是百分比（= 错误数/调用量×100），避免汇总时被误当小数再乘 100
        lines.add("口径：错误率与 4xx 率均为百分比（如 0.078 = 0.078%，可用「错误数/调用量」复核）；P99 单位毫秒");
        if (risks.isEmpty()) {
            lines.add("未发现达到阈值的风险：全部服务错误率 < " + trimNum(errorRatePct) + "% 且 P99 < "
                    + trimNum(p99Ms) + "ms（调用量 ≥ " + minCalls + " 的服务才参与判定）");
        }
        for (Risk r : risks) {
            lines.add("【" + Risk.levelText(r.level()) + "】[" + r.source() + "] " + r.item()
                    + (r.detail() == null || r.detail().isBlank() ? "" : " · " + r.detail()));
        }
        if (k8sNote != null) {
            lines.add(k8sNote);
        }
        if (alarmCount > 0) {
            lines.add("提示：窗口内已有 " + alarmCount + " 条告警被自动值守排查过，可在工作台 → 告警值守 查看结论；"
                    + "上面与告警同服务的风险项大概率是同一问题");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timeRange", timeRange);
        result.put("serviceCount", services.size());
        result.put("highCount", high);
        result.put("mediumCount", mid);
        result.put("alarmCount", alarmCount);
        result.put("thresholds", Map.of("errorRatePct", errorRatePct, "p99Ms", p99Ms, "minCalls", minCalls));
        result.put("risks", risks.stream().map(r -> Map.of(
                "level", Risk.levelText(r.level()), "source", r.source(),
                "service", r.service() == null ? "" : r.service(),
                "item", r.item(), "detail", r.detail() == null ? "" : r.detail())).toList());
        // 完整服务指标也给到结果里：汇总步可以引用（如「列出错误率 Top5」而不必重新巡检）
        List<Map<String, Object>> svcList = new ArrayList<>();
        for (SvcStat s : services) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", s.name());
            m.put("calls", s.calls());
            m.put("errors", s.errors());
            m.put("errorPct", round2(s.errorPct()));
            m.put("p99Ms", round2(s.p99Ms()));
            m.put("fourxxPct", round2(s.fourxxPct()));
            if (s.errors() > 0 || s.p99Ms() >= p99Ms) {
                svcList.add(m);
            }
        }
        result.put("watch", svcList);

        String summary = risks.isEmpty()
                ? "健康巡检完成：" + services.size() + " 个服务，未发现达到阈值的风险"
                : "健康巡检完成：" + services.size() + " 个服务，高风险 " + high + " · 中风险 " + mid
                        + " · 最高风险 " + risks.get(0).item();
        return new ToolResult("list", result, lines, summary);
    }

    /* ---------- 风险判定与信号采集 ---------- */

    /**
     * 服务指标风险分级（规则透明，供单测覆盖）：
     * 高 = 错误率 ≥ 阈值（且调用量达标）；中 = P99 ≥ 阈值 或 错误率过半阈值但调用量翻倍。
     * 调用量不足 minCalls 的服务不下结论：几个请求里错一个就是 100%，是小样本不是风险。
     */
    static int classify(SvcStat s, double errorRatePct, double p99Ms, long minCalls) {
        if (s.calls() < minCalls) {
            return 0;
        }
        if (s.errorPct() >= errorRatePct) {
            return 2;
        }
        if (s.p99Ms() >= p99Ms) {
            return 1;
        }
        if (s.errorPct() >= errorRatePct / 2 && s.calls() >= minCalls * 2) {
            return 1;
        }
        return 0;
    }

    private static String serviceDetail(SvcStat s) {
        return "错误率 " + trimNum(s.errorPct()) + "%（" + s.errors() + "/" + s.calls() + "）"
                + " · P99 " + trimNum(s.p99Ms()) + "ms · 4xx " + trimNum(s.fourxxPct()) + "%";
    }

    /** 解析 signoz_list_services 的 JSON 载荷（data 数组 + pagination），字段缺失时按 0 容错 */
    static List<SvcStat> parseServices(String payload) {
        List<SvcStat> out = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return out;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                // 部分网关把载荷再包一层字符串，尝试二次解析
                data = MAPPER.readTree(root.path("data").asText("")).path("data");
            }
            for (JsonNode n : data) {
                String name = n.path("serviceName").asText("");
                if (name.isBlank()) {
                    continue;
                }
                // errorRate / fourXXRate 服务端返回的已是百分数（实测 = 错误数/调用量 × 100），
                // 不能再 ×100，否则 0.078% 会被放大成 7.8% 触发误报
                out.add(new SvcStat(name,
                        n.path("numCalls").asLong(0),
                        n.path("numErrors").asLong(0),
                        n.path("errorRate").asDouble(0),
                        n.path("p99").asDouble(0) / 1_000_000.0,
                        n.path("avgDuration").asDouble(0) / 1_000_000.0,
                        n.path("fourXXRate").asDouble(0),
                        n.path("num4XX").asLong(0)));
            }
        } catch (Exception ex) {
            // 解析失败按无数据处理，调用方拿空列表给「无数据」提示，比抛异常中断巡检更符合预期
            return new ArrayList<>();
        }
        return out;
    }

    /** K8s 异常 Pod → 风险项：CrashLoop/Pending/未就绪 = 高；重启 ≥10 次 = 中 */
    private List<Risk> k8sRisks(String cluster) throws Exception {
        JsonNode root = MAPPER.readTree(kuboard.get(cluster, "/api/v1/pods?limit=500"));
        List<Risk> out = new ArrayList<>();
        for (JsonNode p : root.path("items")) {
            String name = p.path("metadata").path("name").asText("");
            String ns = p.path("metadata").path("namespace").asText("");
            if (name.isBlank()) {
                continue;
            }
            String phase = p.path("status").path("phase").asText("Unknown");
            int restarts = 0;
            int ready = 0;
            int total = 0;
            String waiting = "";
            for (JsonNode cs : p.path("status").path("containerStatuses")) {
                total++;
                restarts += cs.path("restartCount").asInt(0);
                if (cs.path("ready").asBoolean(false)) {
                    ready++;
                }
                String reason = cs.path("state").path("waiting").path("reason").asText("");
                if (!reason.isBlank()) {
                    waiting = reason;
                }
            }
            boolean notRunning = !phase.equals("Running");
            boolean notReady = total > 0 && ready < total;
            if (notRunning || notReady || !waiting.isBlank()) {
                out.add(new Risk(2, "K8s", ns, name,
                        "状态 " + phase + " · 就绪 " + ready + "/" + total
                                + (waiting.isBlank() ? "" : " · [" + waiting + "]") + " · 重启 " + restarts + " 次"));
            } else if (restarts >= 10) {
                out.add(new Risk(1, "K8s", ns, name, "运行中但累计重启 " + restarts + " 次（疑似反复崩溃）"));
            }
            if (out.size() >= 20) {
                break; // 异常过多时先截断：清单太长反而看不出重点
            }
        }
        return out;
    }

    /** 窗口内告警值守条数（去重前自然计数；拉最近 100 条按时间过滤足够） */
    private int recentAlarms(String timeRange) {
        try {
            int hours = (int) Math.ceil(rangeHours(timeRange));
            LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, hours));
            int count = 0;
            for (AlarmStore.Record r : alarmStore.list(100, 0)) {
                try {
                    if (!LocalDateTime.parse(r.receivedAt(), TS).isBefore(since)) {
                        count++;
                    }
                } catch (Exception ignored) {
                    // 时间格式异常的历史记录跳过
                }
            }
            return count;
        } catch (Exception ex) {
            log.debug("统计窗口内告警记录失败：{}", ex.toString());
            return 0;
        }
    }

    /** "30m"/"6h"/"7d" → 小时数；解析失败按 6h（与默认窗口一致） */
    static double rangeHours(String range) {
        if (range == null || range.isBlank()) {
            return 6;
        }
        String v = range.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.endsWith("m")) {
                return Double.parseDouble(v.substring(0, v.length() - 1)) / 60.0;
            }
            if (v.endsWith("h")) {
                return Double.parseDouble(v.substring(0, v.length() - 1));
            }
            if (v.endsWith("d")) {
                return Double.parseDouble(v.substring(0, v.length() - 1)) * 24;
            }
            return Double.parseDouble(v);
        } catch (Exception ex) {
            return 6;
        }
    }

    /* ---------- 小工具 ---------- */

    private static double parseDouble(Object v, double def) {
        if (v == null) {
            return def;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String str(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static String trimNum(double v) {
        return v >= 100 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
