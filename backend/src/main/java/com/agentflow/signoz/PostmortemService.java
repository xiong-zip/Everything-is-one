package com.agentflow.signoz;

import com.agentflow.alarm.AlarmStore;
import com.agentflow.k8s.KuboardClient;
import com.agentflow.llm.LlmClient;
import com.agentflow.tool.ChangeCorrelationTool;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键故障报告（postmortem）：把一次故障散落在各处的证据自动串成一份结构化报告——
 *
 *   链路分析（根因/传播链）＋ 变更关联（谁改坏的）＋ K8s 实例状态 ＋ 告警值守时间线
 *   → 影响面 / 时间线 / 根因分析 / 处置建议 / 待办事项
 *
 * 双引擎：配置了 LLM 用模型归纳（引用证据、不编造）；未配置时用模板如实拼装各证据段，
 * 结构完整但无归纳——与全局「模拟模式」的口径一致。
 *
 * 只读：不改任何数据、不写分析记录（报告是对既有证据的视图，归档仍走 signoz.case）。
 */
@Service
public class PostmortemService {

    private static final Logger log = LoggerFactory.getLogger(PostmortemService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_K8S_SERVICES = 3;
    private static final int MAX_K8S_PODS = 5;

    /** 生成结果：engine = llm / template */
    public record Report(String traceId, String engine, String markdown, Map<String, Object> meta) {
    }

    /** 模板引擎需要的全部证据（纯数据，renderTemplate 纯函数可单测） */
    record Evidence(String traceId, String traceTime, String env, List<String> services,
                    boolean failed, String failurePoint, String errorClass, String signature,
                    String totalMs, String kbSection, String digestText,
                    List<String> changeLines, List<String> k8sLines, List<TimelineItem> timeline,
                    List<String> alarmLines) {
    }

    /** 时间线条目（按时间排序后进报告） */
    record TimelineItem(String time, String event) {
    }

    private final SigNozMcpClient signozClient;
    private final TraceFetcher traceFetcher;
    private final IncidentKb incidentKb;
    private final ChangeCorrelationTool changeTool;
    private final KuboardClient kuboard;
    private final AlarmStore alarmStore;
    private final LlmClient llm;
    private final String defaultCluster;

    public PostmortemService(SigNozMcpClient signozClient, TraceFetcher traceFetcher, IncidentKb incidentKb,
                             ChangeCorrelationTool changeTool, KuboardClient kuboard, AlarmStore alarmStore,
                             LlmClient llm, @Value("${agentflow.kuboard.cluster:dev}") String defaultCluster) {
        this.signozClient = signozClient;
        this.traceFetcher = traceFetcher;
        this.incidentKb = incidentKb;
        this.changeTool = changeTool;
        this.kuboard = kuboard;
        this.alarmStore = alarmStore;
        this.llm = llm;
        this.defaultCluster = defaultCluster == null || defaultCluster.isBlank() ? "dev" : defaultCluster.trim();
    }

    /** 聚合证据并生成报告；trace 不存在时抛 IllegalArgumentException（界面转 400 提示） */
    public Report generate(String traceId, String requestedRange) {
        if (!signozClient.isConfigured()) {
            throw new IllegalArgumentException("未配置 SigNoz MCP 地址（.env 设置 SIGNOZ_MCP_URL 后重启），无法生成故障报告");
        }
        TraceFetcher.Fetched fetched;
        try {
            fetched = traceFetcher.fetch(traceId, requestedRange);
        } catch (Exception ex) {
            throw new IllegalArgumentException("查询 SigNoz 失败：" + ex.getMessage());
        }
        if (!fetched.found()) {
            throw new IllegalArgumentException("未在 SigNoz 查到 trace " + traceId
                    + "（已尝试默认与 7d 两个时间窗）；请确认 trace ID 与发生时间");
        }

        List<TraceSpan> spans = fetched.spans();
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);
        IncidentKb.Match match = incidentKb.match(fp, IncidentKb.servicesOf(spans));
        String kbSection = match.actionable() ? incidentKb.renderMatchSection(match) : null;
        String digest = TraceDigest.render(traceId, fetched.rangeUsed(), spans, fetched.logs(),
                kbSection, fetched.notice());

        List<String> services = IncidentKb.servicesOf(spans);
        String firstSeen = IncidentKb.timeInfo(spans).firstSeen();

        // 变更关联：后台调用不能交互，映射不唯一/未配置时如实记为「跳过」而不是出卡卡死
        List<String> changeLines = collectChanges(traceId);
        List<String> k8sLines = collectK8s(services);
        List<AlarmStore.Record> alarms = collectAlarms(traceId, services, firstSeen);
        List<TimelineItem> timeline = buildTimeline(firstSeen, alarms, changeLines);

        Evidence ev = new Evidence(traceId,
                firstSeen.length() >= 16 ? firstSeen.substring(0, 16).replace('T', ' ') : firstSeen,
                envOf(spans), services, fetched.failed(),
                failurePoint(spans), fp.errorClass(), TraceDigest.renderFingerprint(fp),
                totalMs(spans), kbSection, digest,
                changeLines, k8sLines, timeline,
                alarms.stream().map(PostmortemService::alarmLine).toList());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("traceId", traceId);
        meta.put("traceTime", ev.traceTime());
        meta.put("services", services);
        meta.put("failed", fetched.failed());
        meta.put("failurePoint", ev.failurePoint());
        meta.put("kbCaseId", match.actionable() && match.entry() != null ? match.entry().id() : "");
        meta.put("changeCount", changeLines.size());
        meta.put("k8sChecked", !k8sLines.isEmpty());
        meta.put("alarmCount", alarms.size());

        if (llm.isEnabled()) {
            try {
                String markdown = llm.chat(SYSTEM_PROMPT, llmPrompt(ev), LlmClient.PURPOSE_POSTMORTEM);
                return new Report(traceId, "llm", markdown, meta);
            } catch (Exception ex) {
                log.warn("LLM 生成故障报告失败，回退模板拼装：{}", ex.getMessage());
            }
        }
        return new Report(traceId, "template", renderTemplate(ev), meta);
    }

    /* ---------- 证据采集 ---------- */

    private List<String> collectChanges(String traceId) {
        List<String> lines = new ArrayList<>();
        try {
            ToolResult cr = changeTool.execute(Map.of("traceId", traceId), "生成故障报告 " + traceId);
            if (cr.clarify() != null) {
                lines.add("（服务映射不唯一，本次未自动关联代码变更；可在工作台 → 服务映射 固化映射后重试）");
            } else if (cr.list() != null) {
                for (String l : cr.list()) {
                    if (lines.size() < 12) {
                        lines.add(l);
                    }
                }
            } else {
                lines.add("（变更关联不可用：" + cr.summary() + "）");
            }
        } catch (Exception ex) {
            lines.add("（变更关联失败：" + ex.getMessage() + "）");
        }
        return lines;
    }

    /** 涉及服务（前 3 个）在集群里的实例状态：异常 Pod 逐个列出，全部正常也写明「实例正常」 */
    private List<String> collectK8s(List<String> services) {
        List<String> lines = new ArrayList<>();
        if (!kuboard.isConfigured() || services.isEmpty()) {
            return lines;
        }
        try {
            JsonNode pods = MAPPER.readTree(kuboard.get(defaultCluster, "/api/v1/pods?limit=500"));
            List<JsonNode> items = new ArrayList<>();
            pods.path("items").forEach(items::add);
            for (String svc : services.subList(0, Math.min(MAX_K8S_SERVICES, services.size()))) {
                int anomalies = 0;
                List<String> svcLines = new ArrayList<>();
                for (JsonNode p : items) {
                    String name = p.path("metadata").path("name").asText("");
                    String ns = p.path("metadata").path("namespace").asText("");
                    if (!name.startsWith(svc)) {
                        continue;
                    }
                    String phase = p.path("status").path("phase").asText("?");
                    int ready = 0;
                    int total = 0;
                    int restarts = 0;
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
                    boolean problem = !phase.equals("Running") || (total > 0 && ready < total) || !waiting.isBlank();
                    if (problem || restarts >= 10) {
                        anomalies++;
                        if (svcLines.size() < MAX_K8S_PODS) {
                            svcLines.add("⚠ " + name + "（" + ns + "）状态 " + phase + " · 就绪 " + ready + "/" + total
                                    + (waiting.isBlank() ? "" : " · [" + waiting + "]") + " · 重启 " + restarts + " 次");
                        }
                    }
                }
                if (!svcLines.isEmpty()) {
                    lines.add(svc + "：异常实例 " + anomalies + " 个");
                    lines.addAll(svcLines);
                } else {
                    int found = (int) items.stream().filter(p ->
                            p.path("metadata").path("name").asText("").startsWith(svc)).count();
                    lines.add(svc + "：" + (found == 0 ? "集群内没有同名实例（可能不在默认集群）" : "实例全部正常（" + found + " 个）"));
                }
            }
        } catch (Exception ex) {
            lines.add("（K8s 状态查询失败：" + ex.getMessage() + "）");
        }
        return lines;
    }

    /** 与本次故障相关的值守记录：trace ID 直接命中，或同服务且时间在故障前后一天内 */
    private List<AlarmStore.Record> collectAlarms(String traceId, List<String> services, String firstSeen) {
        LocalDateTime since;
        OffsetDateTime traceAt = parseLocal(firstSeen);
        since = traceAt != null ? traceAt.toLocalDateTime().minusDays(1) : LocalDateTime.now().minusDays(2);
        List<AlarmStore.Record> out = new ArrayList<>();
        try {
            for (AlarmStore.Record r : alarmStore.list(200, 0)) {
                boolean traceHit = traceId.equalsIgnoreCase(r.traceId());
                if (!traceHit && (r.service() == null || !services.contains(r.service()))) {
                    continue;
                }
                if (!traceHit) {
                    try {
                        if (LocalDateTime.parse(r.receivedAt(), TS).isBefore(since)) {
                            continue;
                        }
                    } catch (Exception ignored) {
                        continue;
                    }
                }
                out.add(r);
                if (out.size() >= 10) {
                    break;
                }
            }
        } catch (Exception ex) {
            log.debug("收集告警值守记录失败：{}", ex.toString());
        }
        return out;
    }

    private static String alarmLine(AlarmStore.Record r) {
        return (r.receivedAt() == null ? "?" : r.receivedAt()) + " [" + (r.severity() == null ? "?" : r.severity()) + "] "
                + (r.alertName() == null ? "" : r.alertName())
                + (r.service() == null || r.service().isBlank() ? "" : "（" + r.service() + "）")
                + " → 值守" + stateText(r.state());
    }

    private static String stateText(String state) {
        return switch (state == null ? "" : state) {
            case "done" -> "已排查";
            case "running" -> "排查中";
            case "skipped" -> "已跳过";
            case "error" -> "排查失败";
            default -> state == null ? "未知" : state;
        };
    }

    /** 故障时间线：告警触发 / 值守结论 / 嫌疑变更提交时间，按时间排序（纯函数，单测覆盖） */
    static List<TimelineItem> buildTimeline(String firstSeen, List<AlarmStore.Record> alarms, List<String> changeLines) {
        List<TimelineItem> out = new ArrayList<>();
        String traceTime = firstSeen != null && firstSeen.length() >= 16
                ? firstSeen.substring(0, 16).replace('T', ' ') : firstSeen;
        out.add(new TimelineItem(traceTime == null ? "?" : traceTime, "链路发生（本次故障证据）"));
        for (AlarmStore.Record r : alarms) {
            if (r.receivedAt() != null && !r.receivedAt().isBlank()) {
                out.add(new TimelineItem(r.receivedAt(), "告警触发 [" + (r.severity() == null ? "?" : r.severity()) + "] "
                        + (r.alertName() == null ? "" : r.alertName())
                        + (r.state() != null && !"running".equals(r.state()) ? "，值守结论：" + oneLine(r.summary(), 80) : "")));
            }
        }
        // 变更行形如 "#1 [7分] abc1234 · 作者 · 09-08 14:30 · 提交标题（理由）"：把时间提出来做时间线节点
        for (String l : changeLines) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\b(\\d{2}-\\d{2} \\d{2}:\\d{2})\\b").matcher(l == null ? "" : l);
            if (l != null && l.startsWith("#") && m.find()) {
                out.add(new TimelineItem(m.group(1), "嫌疑变更：" + oneLine(l.replaceFirst("^#\\d+ \\[\\d+分\\] [0-9a-f]+ · [^·]+ · [\\d-: ]+ · ", ""), 60)));
            }
        }
        out.sort(Comparator.comparing(t -> sortKey(t.time())));
        return out;
    }

    /**
     * 时间线排序键：告警是 "yyyy-MM-dd HH:mm:ss"，变更行只有 "MM-dd HH:mm"（当年提交），
     * 直接字符串比较会把变更排到最前。缺年份的按当年补齐后再比。
     */
    static String sortKey(String time) {
        String t = time == null ? "" : time.trim();
        if (t.matches("\\d{2}-\\d{2} \\d{2}:\\d{2}")) {
            return java.time.Year.now().getValue() + "-" + t;
        }
        return t;
    }

    /* ---------- 生成引擎 ---------- */

    private static final String SYSTEM_PROMPT = """
            你是资深 SRE，负责把一次故障的全部证据整理成一份可直接归档的故障报告（Markdown）。
            要求：
            1. 固定章节顺序：## 影响面 / ## 时间线 / ## 根因分析 / ## 处置建议 / ## 待办事项。
            2. 只使用给定证据，不得编造；不确定的结论要标注「待确认」并说明缺什么证据。
            3. 根因分析要引用具体证据（失败 span、异常类、嫌疑变更、历史案例），并给出置信度（高/中/低）。
            4. 处置建议与待办要可执行（谁/做什么/怎么验证），没有依据就写「需人工确认」。
            5. 语言简洁，中文，直接输出 Markdown 正文（不要代码块包裹）。""";

    private static String llmPrompt(Evidence ev) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 故障基本信息\n");
        sb.append("- trace ID：").append(ev.traceId()).append('\n');
        sb.append("- 发生时间：").append(ev.traceTime()).append('\n');
        if (!ev.env().isBlank()) {
            sb.append("- 环境：").append(ev.env()).append('\n');
        }
        sb.append("- 涉及服务：").append(String.join("、", ev.services())).append('\n');
        sb.append("- 失败点：").append(ev.failurePoint().isBlank() ? "（成功链路，无失败 span）" : ev.failurePoint()).append('\n');
        sb.append("- 指纹：").append(ev.errorClass()).append(" ｜ ").append(ev.signature()).append('\n');
        sb.append("- 总耗时：").append(ev.totalMs()).append('\n');
        sb.append("\n## 链路分析摘要（根因与传播链证据）\n").append(ev.digestText()).append("\n");
        sb.append("\n## 代码变更关联（故障前的嫌疑提交）\n");
        appendLines(sb, ev.changeLines());
        if (!ev.k8sLines().isEmpty()) {
            sb.append("\n## K8s 实例状态\n");
            appendLines(sb, ev.k8sLines());
        }
        if (!ev.timeline().isEmpty()) {
            sb.append("\n## 已知时间线（按时间排序）\n");
            for (TimelineItem t : ev.timeline()) {
                sb.append("- ").append(t.time()).append(" ").append(t.event()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 模板引擎：如实拼装各证据段（无归纳），保证未配置 LLM 时报告结构仍完整 */
    static String renderTemplate(Evidence ev) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 故障报告 · ").append(ev.traceId()).append("\n\n");
        sb.append("- 发生时间：").append(ev.traceTime());
        if (!ev.env().isBlank()) {
            sb.append(" · 环境 ").append(ev.env());
        }
        sb.append("\n- 涉及服务：").append(String.join("、", ev.services())).append('\n');
        sb.append("- 失败点：").append(ev.failurePoint().isBlank() ? "（成功链路）" : ev.failurePoint()).append('\n');
        sb.append("- 指纹：").append(ev.errorClass()).append(" ｜ ").append(ev.signature())
                .append(" · 总耗时 ").append(ev.totalMs()).append("\n\n");

        sb.append("## 影响面\n\n");
        sb.append("涉及服务 ").append(ev.services().size()).append(" 个（").append(String.join("、", ev.services()))
                .append("）；").append(ev.failed() ? "链路失败，业务受影响" : "链路最终成功（以耗时/降级影响为主）")
                .append("。具体影响范围需结合业务确认。\n\n");

        sb.append("## 时间线\n\n");
        for (TimelineItem t : ev.timeline()) {
            sb.append("- ").append(t.time()).append(" ").append(t.event()).append('\n');
        }
        sb.append('\n');

        sb.append("## 根因分析\n\n");
        sb.append("以下为链路分析结论（程序生成，未经模型归纳）：\n\n")
                .append(ev.digestText()).append("\n\n");
        sb.append("## 处置建议\n\n");
        if (ev.kbSection() != null) {
            sb.append("知识库命中历史案例，处置方案：\n\n").append(ev.kbSection())
                    .append("\n\n请先复核指纹一致性再复用历史处置。\n\n");
        } else {
            sb.append("知识库未命中历史案例。建议按失败点定位（").append(ev.failurePoint().isBlank() ? "见上" : ev.failurePoint())
                    .append("），结合下方嫌疑变更人工确认根因。\n\n");
        }
        if (!ev.k8sLines().isEmpty()) {
            sb.append("## K8s 实例状态\n\n");
            appendLines(sb, ev.k8sLines());
            sb.append('\n');
        }
        sb.append("## 嫌疑变更（谁改坏的）\n\n");
        appendLines(sb, ev.changeLines());
        sb.append('\n');
        sb.append("## 待办事项\n\n");
        sb.append("1. 确认根因（参考根因分析与嫌疑变更）\n");
        sb.append("2. 按处置建议修复并验证（重放关键接口或观察错误率）\n");
        sb.append("3. 修复确认后在对话里执行「归档案例 ")
                .append(ev.traceId).append("」，把本次结论沉淀进故障案例库\n");
        sb.append("\n> 本报告由模板拼装（未配置 LLM），仅聚合证据，不含模型归纳。\n");
        return sb.toString();
    }

    private static void appendLines(StringBuilder sb, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            sb.append("（无）\n");
            return;
        }
        for (String l : lines) {
            sb.append(l).append('\n');
        }
    }

    private static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String envOf(List<TraceSpan> spans) {
        for (TraceSpan s : spans) {
            if (s.env() != null && !s.env().isBlank()) {
                return s.env();
            }
        }
        return "";
    }

    private static String failurePoint(List<TraceSpan> spans) {
        TraceSpan origin = TraceDigest.errorOrigin(spans);
        return origin == null ? "" : origin.label();
    }

    private static String totalMs(List<TraceSpan> spans) {
        TraceSpan root = TraceDigest.mainRoot(spans);
        return root == null ? "?" : TraceDigest.fmt(root.durationMs()) + "ms";
    }

    /** firstSeen（ISO 带时区）→ 本地 OffsetDateTime；解析失败返回 null */
    static OffsetDateTime parseLocal(String isoTs) {
        try {
            return OffsetDateTime.parse(isoTs).atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (Exception ex) {
            return null;
        }
    }
}
