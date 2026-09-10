package com.agentflow.signoz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链路分析核心（纯逻辑，不依赖网络，便于单测）：
 * 解析 SigNoz 返回的 span/日志载荷 → 推导故障指纹 → 渲染成"根因优先"的紧凑摘要。
 *
 * 摘要要按重要性倒序排列：引擎只把结果的前 1600 字符喂给写作 LLM，
 * 因此结论、失败链、关键异常必须放在最前面，span 清单等明细放尾部。
 */
public final class TraceDigest {

    private TraceDigest() {
    }

    /* ================= 数据结构 ================= */

    public record LogLine(String severity, String service, String body, String timestamp) {
    }

    /** 解析结果：notice 非空时表示服务端有提示（如"trace 存在但在所选时间范围外"） */
    public record ParsedSpans(List<TraceSpan> spans, String notice) {
    }

    /** 故障指纹：判断"是不是同一个毛病"的四要素 + 检索关键词 */
    public record Fingerprint(String errorClass, String signature, String span, String status,
                              List<String> keywords) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 摘要尾部 span 清单的行数上限：大链路只列最慢的若干个，避免前端列表失控 */
    private static final int MAX_SPAN_INVENTORY = 40;

    /* ================= 载荷解析 ================= */

    /**
     * 解析 signoz_get_trace_details 的返回载荷。
     * 结构：{status, data:{meta, warning, data:{results:[{rows:[{data:{...span}}]}]}}}
     */
    public static ParsedSpans parseSpans(String payload) {
        List<TraceSpan> spans = new ArrayList<>();
        String notice = null;
        JsonNode root = readTree(payload);
        notice = warningText(root);
        JsonNode rows = firstRows(root);
        if (rows == null || rows.isNull()) {
            return new ParsedSpans(spans, notice);
        }
        for (JsonNode row : rows) {
            JsonNode d = row.has("data") ? row.get("data") : row;
            if (d == null || d.isNull()) {
                continue;
            }
            spans.add(new TraceSpan(
                    text(d, "service.name"),
                    text(d, "name"),
                    firstText(d, "spanID", "span_id"),
                    text(d, "parentSpanID"),
                    d.path("durationNano").asLong(0L),
                    d.path("hasError").asBoolean(false),
                    d.path("statusCodeString").asText(""),
                    d.path("statusMessage").asText(""),
                    d.path("spanKind").asText(""),
                    firstText(d, "timestamp"),
                    firstText(d, "http.response.status_code", "responseStatusCode"),
                    firstText(d, "deployment.environment")));
        }
        return new ParsedSpans(spans, notice);
    }

    /** 解析 signoz_search_logs 的返回载荷，抽取 ERROR/WARN 日志正文 */
    public static List<LogLine> parseLogs(String payload) {
        List<LogLine> out = new ArrayList<>();
        JsonNode rows = firstRows(readTree(payload));
        if (rows == null || rows.isNull()) {
            return out;
        }
        for (JsonNode row : rows) {
            JsonNode d = row.has("data") ? row.get("data") : row;
            if (d == null || d.isNull()) {
                continue;
            }
            String body = firstText(d, "body", "message", "log.body");
            if (body == null || body.isBlank()) {
                continue;
            }
            out.add(new LogLine(firstText(d, "severity_text", "severityText"),
                    firstText(d, "service.name"),
                    body,
                    firstText(d, "timestamp")));
        }
        return out;
    }

    /** 服务端提示：如 trace 存在于别的时间段（引导扩时间窗，而非误报"未找到"） */
    private static String warningText(JsonNode root) {
        JsonNode warning = root.path("data").path("warning");
        if (warning.isMissingNode() || warning.isNull()) {
            return null;
        }
        JsonNode warnings = warning.path("warnings");
        if (warnings.isArray() && !warnings.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode w : warnings) {
                String m = w.path("message").asText("");
                if (!m.isBlank()) {
                    sb.append(m).append("；");
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        String msg = warning.path("message").asText("");
        return msg.isBlank() ? null : msg;
    }

    private static JsonNode firstRows(JsonNode root) {
        JsonNode results = root.path("data").path("data").path("results");
        if (results.isArray() && !results.isEmpty()) {
            return results.get(0).path("rows");
        }
        return null;
    }

    private static JsonNode readTree(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("SigNoz 返回空载荷");
        }
        try {
            return MAPPER.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("SigNoz 返回载荷不是合法 JSON：" + truncate(payload, 200), ex);
        }
    }

    /* ================= 结构推导 ================= */

    /** 根 span：parentSpanID 不在本 trace 的 spanID 集合内 */
    public static List<TraceSpan> roots(List<TraceSpan> spans) {
        Set<String> ids = new HashSet<>();
        for (TraceSpan s : spans) {
            ids.add(s.spanId());
        }
        List<TraceSpan> roots = new ArrayList<>();
        for (TraceSpan s : spans) {
            if (s.parentSpanId() == null || s.parentSpanId().isBlank() || !ids.contains(s.parentSpanId())) {
                roots.add(s);
            }
        }
        return roots;
    }

    /** 主根 span：耗时最长的根（多根时取最主要的那条链路） */
    public static TraceSpan mainRoot(List<TraceSpan> spans) {
        List<TraceSpan> roots = roots(spans);
        return roots.stream().max(Comparator.comparingLong(TraceSpan::durationNano)).orElse(null);
    }

    /**
     * 失败源头：报错 span 中层级最深的那个。
     * 失败会逐层向 root 传播（子 span 先出错、父 span 只是转发），越深越接近根因。
     */
    public static TraceSpan errorOrigin(List<TraceSpan> spans) {
        List<TraceSpan> errors = new ArrayList<>();
        for (TraceSpan s : spans) {
            if (s.hasError()) {
                errors.add(s);
            }
        }
        if (errors.isEmpty()) {
            return null;
        }
        Map<String, Integer> depth = depths(spans);
        return errors.stream()
                .max(Comparator
                        .comparingInt((TraceSpan s) -> depth.getOrDefault(s.spanId(), 0))
                        .thenComparingInt(s -> s.statusMessage().length())
                        .thenComparingLong(TraceSpan::durationNano))
                .orElse(null);
    }

    /** span 相对 root 的深度（parentSpanID 成环时按已访问截断） */
    static Map<String, Integer> depths(List<TraceSpan> spans) {
        Map<String, TraceSpan> byId = new HashMap<>();
        for (TraceSpan s : spans) {
            byId.put(s.spanId(), s);
        }
        Map<String, Integer> depth = new HashMap<>();
        for (TraceSpan s : spans) {
            resolveDepth(s, byId, depth, new HashSet<>(), 0);
        }
        return depth;
    }

    private static int resolveDepth(TraceSpan s, Map<String, TraceSpan> byId, Map<String, Integer> depth,
                                    Set<String> visiting, int guard) {
        Integer cached = depth.get(s.spanId());
        if (cached != null) {
            return cached;
        }
        if (guard > 200 || !visiting.add(s.spanId())) {
            return 0;
        }
        TraceSpan parent = s.parentSpanId() == null ? null : byId.get(s.parentSpanId());
        int d = parent == null ? 0 : resolveDepth(parent, byId, depth, visiting, guard + 1) + 1;
        visiting.remove(s.spanId());
        depth.put(s.spanId(), d);
        return d;
    }

    /** 从 root 到指定 span 的路径（用于画失败传播链） */
    public static List<TraceSpan> pathToRoot(TraceSpan span, List<TraceSpan> spans) {
        Map<String, TraceSpan> byId = new HashMap<>();
        for (TraceSpan s : spans) {
            byId.put(s.spanId(), s);
        }
        List<TraceSpan> path = new ArrayList<>();
        TraceSpan cur = span;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur.spanId())) {
            path.add(0, cur);
            cur = cur.parentSpanId() == null ? null : byId.get(cur.parentSpanId());
        }
        return path;
    }

    /* ================= 指纹推导 ================= */

    private static final Pattern EXCEPTION = Pattern.compile("\\b([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:Exception|Error))\\b");
    private static final Pattern MAY_INVOLVE = Pattern.compile("The error may involve\\s+([\\w.$]+)");
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)");

    /**
     * 推导故障指纹。所有取值都来自 span 里真实出现的内容，缺失就留明确的"未上报"占位，不猜。
     */
    public static Fingerprint fingerprint(List<TraceSpan> spans) {
        TraceSpan origin = errorOrigin(spans);
        TraceSpan root = mainRoot(spans);
        if (origin == null) {
            // 成功链路：指纹只保留性能/位置信息，用于归档"稳定慢点"
            String span = root == null ? "未上报" : root.label();
            String status = root == null ? "未上报" : otelStatus(root);
            return new Fingerprint("无异常（成功链路）", span, span, status, List.of());
        }
        String msg = origin.statusMessage() == null ? "" : origin.statusMessage();
        String errorClass = deepestException(msg);
        String signature = deriveSignature(msg, origin);
        String status = otelStatus(origin) + contradiction(origin, root);
        return new Fingerprint(errorClass, signature, origin.label(), status,
                deriveKeywords(msg, errorClass, signature));
    }

    /** 异常链里最后一个异常类即最深根因（消息按 "Cause:" 逐层追加） */
    static String deepestException(String msg) {
        Matcher m = EXCEPTION.matcher(msg);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last == null ? "未上报异常类型" : last;
    }

    /** 识别特征：优先 MyBatis 的 may-involve 方法，其次 SQL 主表，最后退回失败 span 自身 */
    static String deriveSignature(String msg, TraceSpan origin) {
        Matcher mi = MAY_INVOLVE.matcher(msg);
        String last = null;
        while (mi.find()) {
            last = mi.group(1);
        }
        if (last != null) {
            return shortenFqcn(last);
        }
        Matcher mt = SQL_TABLE.matcher(msg);
        String table = null;
        while (mt.find()) {
            String t = mt.group(1);
            if (!isSqlKeywordTable(t)) {
                table = t;
                break;
            }
        }
        if (table != null) {
            return stripSchema(table);
        }
        return origin.label();
    }

    private static boolean isSqlKeywordTable(String t) {
        String u = t.toUpperCase();
        return u.equals("SELECT") || u.equals("WHERE") || u.equals("DUAL");
    }

    /** com.zoe.pay.business.dao.SecurityDepositDetailDao.selectListByQuery → SecurityDepositDetailDao.selectListByQuery */
    static String shortenFqcn(String fqcn) {
        String[] parts = fqcn.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return fqcn;
    }

    static String stripSchema(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }

    private static List<String> deriveKeywords(String msg, String errorClass, String signature) {
        Set<String> kw = new LinkedHashSet<>();
        String simple = errorClass.contains(".") ? errorClass.substring(errorClass.lastIndexOf('.') + 1) : errorClass;
        if (!simple.startsWith("未上报")) {
            kw.add(simple);
        }
        if (signature != null && !signature.isBlank() && !signature.contains(" / ")) {
            kw.add(signature);
        }
        Matcher mt = SQL_TABLE.matcher(msg);
        int n = 0;
        while (mt.find() && n < 2) {
            String t = stripSchema(mt.group(1));
            if (!isSqlKeywordTable(t) && kw.add(t)) {
                n++;
            }
        }
        return new ArrayList<>(kw);
    }

    /** OTel 状态：HTTP 200 但 span 报错是常见陷阱，这里如实呈现两个维度 */
    private static String otelStatus(TraceSpan s) {
        String otel = s.statusCode() == null || s.statusCode().isBlank() ? "Unset" : s.statusCode();
        String http = s.httpStatus() == null ? "" : s.httpStatus().trim();
        return http.isEmpty() ? "OTel " + otel : "HTTP " + http + " + OTel " + otel;
    }

    /** 状态矛盾提示：HTTP 码已被 otelStatus 呈现，这里只补"根 span 没报错"这个易漏判点 */
    private static String contradiction(TraceSpan origin, TraceSpan root) {
        if (root != null && !root.hasError() && origin != root) {
            return "（根 span 无错误，网关层可能误判成功）";
        }
        return "";
    }

    /* ================= 摘要渲染 ================= */

    /**
     * 渲染"根因优先"摘要。kbSection 为知识库命中信息（无命中传 null），
     * notice 为服务端提示（如时间窗提示）。
     */
    public static String render(String traceId, String timeRangeUsed, List<TraceSpan> spans,
                                List<LogLine> logs, String kbSection, String notice) {
        StringBuilder sb = new StringBuilder();
        TraceSpan root = mainRoot(spans);
        TraceSpan origin = errorOrigin(spans);
        int errorCount = 0;
        for (TraceSpan s : spans) {
            if (s.hasError()) {
                errorCount++;
            }
        }
        Set<String> services = new LinkedHashSet<>();
        for (TraceSpan s : spans) {
            services.add(s.service());
        }

        // 1) 结论区：第一屏
        sb.append("Trace ").append(traceId)
                .append(" | 窗口 ").append(timeRangeUsed)
                .append(" | ").append(spans.size()).append(" spans | ").append(services.size()).append(" 服务");
        if (root != null) {
            sb.append(" | 总耗时 ").append(fmt(root.durationMs())).append("ms");
        }
        String env = firstNonBlank(spans, TraceSpan::env);
        if (env != null) {
            sb.append(" | 环境 ").append(env);
        }
        sb.append('\n');
        sb.append("判定：");
        if (origin == null) {
            sb.append("成功链路（无报错 span）");
        } else {
            sb.append("失败链路（").append(errorCount).append(" 个 span 报错）· 失败源头 ")
                    .append(origin.label()).append(" · ").append(otelStatus(origin));
            List<String> caveats = new ArrayList<>();
            if (origin.httpStatus() != null && origin.httpStatus().trim().startsWith("2")) {
                caveats.add("HTTP 成功码但业务失败");
            }
            if (root != null && !root.hasError()) {
                caveats.add("根 span 未标记错误");
            }
            if (!caveats.isEmpty()) {
                sb.append("（").append(String.join("；", caveats)).append("，网关/监控易漏判）");
            }
        }
        sb.append('\n');

        // 2) 知识库命中：命中就直接给历史处置，避免重复推理
        if (kbSection != null && !kbSection.isBlank()) {
            sb.append(kbSection).append('\n');
        }

        // 3) 失败传播链
        if (origin != null) {
            List<TraceSpan> path = pathToRoot(origin, spans);
            sb.append("失败传播链（root → 失败源头）：\n");
            for (int i = 0; i < path.size(); i++) {
                TraceSpan s = path.get(i);
                sb.append("  ").append("  ".repeat(i)).append(i == 0 ? "" : "└ ")
                        .append(s.label()).append(' ').append(fmt(s.durationMs())).append("ms");
                if (s.hasError()) {
                    sb.append(" [Error");
                    if (s.httpStatus() != null && !s.httpStatus().isBlank()) {
                        sb.append('/').append(s.httpStatus());
                    }
                    sb.append(']');
                }
                sb.append('\n');
            }
        }

        // 4) 关键异常原文（最大价值证据，去重后截断）
        if (origin != null) {
            sb.append("失败源头异常：").append(oneLine(origin.statusMessage(), 400)).append('\n');
        }
        List<String> others = new ArrayList<>();
        for (TraceSpan s : spans) {
            if (s.hasError() && s != origin && s.statusMessage() != null && !s.statusMessage().isBlank()) {
                String one = oneLine(s.statusMessage(), 160);
                if (!others.contains(one)) {
                    others.add(one);
                }
            }
        }
        if (!others.isEmpty()) {
            sb.append("其他错误 span 摘要：").append(String.join(" ｜ ", others)).append('\n');
        }

        // 5) 最慢 span
        List<TraceSpan> slow = new ArrayList<>(spans);
        slow.sort(Comparator.comparingLong(TraceSpan::durationNano).reversed());
        sb.append("最慢 span：");
        for (int i = 0; i < Math.min(5, slow.size()); i++) {
            TraceSpan s = slow.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(s.label()).append(' ').append(fmt(s.durationMs())).append("ms");
            if (root != null && root.durationNano() > 0) {
                sb.append('(').append(fmt(s.durationNano() * 100.0 / root.durationNano())).append("%)");
            }
        }
        sb.append('\n');

        // 6) 日志证据
        if (logs != null && !logs.isEmpty()) {
            sb.append("ERROR/WARN 日志 ").append(logs.size()).append(" 条：\n");
            Set<String> seen = new HashSet<>();
            int n = 0;
            for (LogLine l : logs) {
                String one = oneLine(l.body(), 200);
                if (!seen.add(one)) {
                    continue;
                }
                sb.append("  - ").append(one).append('\n');
                if (++n >= 3) {
                    break;
                }
            }
        } else {
            sb.append("ERROR/WARN 日志：无\n");
        }

        // 7) 明确"缺什么"，避免模型在证据不足时编造
        if (origin == null && (logs == null || logs.isEmpty())) {
            sb.append("证据说明：无报错 span 也无错误日志，如需根因请提供大致发生时间与现象。\n");
        }
        if (notice != null && !notice.isBlank()) {
            sb.append("服务端提示：").append(oneLine(notice, 300)).append('\n');
        }

        // 8) 尾部明细（LLM 通常看不到，前端展示用）：大链路必须截断，否则前端列表会长到失控
        int shown = Math.min(spans.size(), MAX_SPAN_INVENTORY);
        sb.append("span 清单");
        sb.append(spans.size() > shown
                ? "（最慢 " + shown + " 个 / 共 " + spans.size() + " 个）："
                : "：");
        for (int i = 0; i < shown; i++) {
            TraceSpan s = slow.get(i);
            sb.append('[').append(s.service()).append(']').append(s.name())
                    .append('(').append(fmt(s.durationMs())).append("ms")
                    .append(s.hasError() ? ",Error" : "").append(") ");
        }
        return sb.toString();
    }

    /** 指纹渲染成"字段：值"多行文本，供报告与归档复用 */
    public static String renderFingerprint(Fingerprint fp) {
        return "指纹 error_class=" + fp.errorClass()
                + " | signature=" + fp.signature()
                + " | span=" + fp.span()
                + " | status=" + fp.status()
                + " | keywords=" + String.join(",", fp.keywords());
    }

    /* ================= 小工具 ================= */

    static String fmt(double v) {
        return String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    /** 压成单行并截断：异常栈原文很长，摘要里只留最关键的头部 */
    public static String oneLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String firstNonBlank(List<TraceSpan> spans, java.util.function.Function<TraceSpan, String> f) {
        for (TraceSpan s : spans) {
            String v = f.apply(s);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return "";
        }
        return v.asText("");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && !v.isNull()) {
                String s = v.asText("");
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
