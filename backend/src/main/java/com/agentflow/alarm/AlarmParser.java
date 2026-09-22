package com.agentflow.alarm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 告警载荷解析：把各家监控平台的 webhook 压成 {@link AlarmEvent}。
 *
 * <p>刻意不做成「按平台分支」的解析器。SigNoz、Alertmanager、Grafana、自建脚本的载荷字段名
 * 各不相同且会随版本改，逐家适配意味着每接一个平台就要改一次代码。这里改成
 * <b>按键名归一化后全树查找</b>：把 {@code service_name} / {@code serviceName} / {@code service.name}
 * 统一成 {@code servicename} 再取值，未知结构也能命中；trace ID 则直接对原始报文做正则，
 * 无论它藏在 labels、annotations 还是自由文本里都能捞出来。
 *
 * <p>代价是可能误命中同名的无关字段，所以字段候选表刻意收窄（例如不认裸的 {@code name}），
 * 且 status 只接受已知取值，认不出就当没写。
 */
public final class AlarmParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 32 位连续十六进制即 trace ID；带横线的 UUID 有 36 位且被横线打断，不会误命中 */
    private static final Pattern TRACE_ID = Pattern.compile("\\b([0-9a-fA-F]{32})\\b");
    private static final int RAW_LIMIT = 4000;

    /** 字段候选表：键名归一化（小写、去掉非字母数字）后比对，靠前的优先 */
    private static final List<String> ALERT_NAME_KEYS =
            List.of("alertname", "rulename", "alert", "title", "eventname");
    private static final List<String> SEVERITY_KEYS =
            List.of("severity", "level", "priority", "prioritylevel", "criticality");
    private static final List<String> STATUS_KEYS =
            List.of("state", "status", "alertstate", "alertstatus");
    /** 服务名的候选键，含 K8s 场景常见的带前缀写法（k8s.service / k8s.deployment） */
    private static final List<String> SERVICE_KEYS =
            List.of("servicename", "service", "k8sservice", "app", "application",
                    "deployment", "k8sdeployment", "workload", "job", "component");
    private static final List<String> MESSAGE_KEYS =
            List.of("message", "description", "summary", "text", "content", "detail", "note", "reason");
    private static final List<String> SOURCE_KEYS =
            List.of("source", "receiver", "integration", "platform", "sender");

    /** status 只认这些取值，其它一律当没写（防止把 HTTP 响应里的 status:200 当成告警状态） */
    private static final Map<String, String> KNOWN_STATUS = Map.of(
            "firing", "firing", "alerting", "firing", "triggered", "firing",
            "resolved", "resolved", "ok", "resolved", "normal", "resolved", "recovered", "resolved");

    private AlarmParser() {
    }

    /** 解析载荷；JSON 解析失败时降级为纯文本（只捞 trace ID，其余留空） */
    public static AlarmEvent parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        Map<String, String> fields = new LinkedHashMap<>();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode root = MAPPER.readTree(trimmed);
                // 先走「真正的那个告警」，再补走根节点，这样告警对象里的字段优先于
                // 根上的公共字段（commonLabels 等）——两者冲突时以具体告警为准
                JsonNode scope = pickAlert(root);
                flatten(scope, fields);
                if (scope != root) {
                    flatten(root, fields);
                }
            } catch (Exception ignored) {
                // 声称是 JSON 但解析不了：当纯文本处理，总比拒绝掉整条告警好
            }
        }
        String traceId = firstTraceId(trimmed);
        String service = pick(fields, SERVICE_KEYS);
        String message = pick(fields, MESSAGE_KEYS);
        if (message == null && fields.isEmpty()) {
            message = firstLine(trimmed);
        }
        String alertName = pick(fields, ALERT_NAME_KEYS);
        String severity = pick(fields, SEVERITY_KEYS);
        String status = pickStatus(fields);
        String source = pick(fields, SOURCE_KEYS);

        String dedupKey = String.join("|",
                alertName == null ? "" : alertName,
                service == null ? "" : service,
                traceId == null ? "" : traceId,
                status == null ? "" : status);
        return new AlarmEvent(nz(source, "webhook"), nz(alertName, ""), nz(severity, ""),
                nz(status, "firing"), nz(service, ""), nz(traceId, ""), nz(message, ""),
                dedupKey, truncate(trimmed, RAW_LIMIT));
    }

    /**
     * Alertmanager 的 {@code alerts} 数组里混着触发与恢复的告警，
     * 挑第一条未恢复的来排查——恢复的那条没有根因可查。
     */
    private static JsonNode pickAlert(JsonNode root) {
        JsonNode alerts = root == null ? null : root.get("alerts");
        if (alerts == null || !alerts.isArray() || alerts.isEmpty()) {
            return root;
        }
        for (JsonNode alert : alerts) {
            String st = normalizeStatus(alert.path("status").path("state").asText(""));
            if (st == null) {
                st = normalizeStatus(alert.path("status").asText(""));
            }
            if (!"resolved".equals(st)) {
                return alert;
            }
        }
        return alerts.get(0);
    }

    /** 深度优先展平：同名字段只保留第一次出现（外层的更接近"整条告警"的语义） */
    private static void flatten(JsonNode node, Map<String, String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v != null && v.isValueNode() && !v.isNull()) {
                    String text = v.asText();
                    if (!text.isBlank()) {
                        out.putIfAbsent(norm(e.getKey()), text.trim());
                    }
                } else if (v != null) {
                    flatten(v, out);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                flatten(item, out);
            }
        }
    }

    private static String pick(Map<String, String> fields, List<String> keys) {
        for (String k : keys) {
            String v = fields.get(k);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String pickStatus(Map<String, String> fields) {
        for (String k : STATUS_KEYS) {
            String v = fields.get(k);
            if (v == null) {
                continue;
            }
            String normalized = normalizeStatus(v);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String normalizeStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return KNOWN_STATUS.get(raw.trim().toLowerCase());
    }

    /** 原文正则捞 trace ID：它常出现在告警描述的自由文本里，按键名找反而容易漏 */
    static String firstTraceId(String text) {
        Matcher m = TRACE_ID.matcher(text);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    private static String firstLine(String text) {
        return truncate(text.split("\n")[0].trim(), 300);
    }

    /** 键名归一化：小写并去掉非字母数字，让 service_name / serviceName / service.name 等价 */
    private static String norm(String key) {
        if (key == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…（已截断）";
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
