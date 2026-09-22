package com.agentflow.signoz;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路分析记录查询工具（只读）：让 Agent 能回答「我自己的排查历史」这类问题。
 *
 * <p>存在的意义是把两件事从「写代码」降级成「说一句话」：
 * <ul>
 *   <li><b>交接班摘要</b>——工作台里加一条定时任务，指令写「汇总过去 12 小时失败链路的分析记录，
 *       生成值班交接摘要」，到点自动推送到群，不需要新增任何调度代码；</li>
 *   <li><b>指纹回归排查</b>——问「哪些故障指纹反复出现」，直接列出同一错误模式重复发生的次数。</li>
 * </ul>
 *
 * <p>只读本机 SQLite 里的分析记录（{@link TraceAnalysisStore}），不查 SigNoz、不消耗额度。
 */
@Component
public class SigNozAnalysesTool implements Tool {

    private static final int DEFAULT_HOURS = 24;
    private static final int DEFAULT_LIMIT = 20;

    private final TraceAnalysisStore store;

    public SigNozAnalysesTool(TraceAnalysisStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "signoz.analyses";
    }

    @Override
    public String description() {
        return "查询本机记录的链路分析历史：过去 N 小时分析过哪些链路、哪些失败了、失败的指纹是什么，"
                + "以及哪些故障指纹反复出现（疑似回归）。"
                + "适合回答「昨晚有哪些故障」「做个值班交接摘要」「最近哪类问题反复发生」「我分析过多少条链路」。"
                + "action=stats 给汇总统计，action=recurring 给反复出现的故障指纹，action=list（默认）列明细。"
                + "只读本机记录，不查询 SigNoz 服务端、不消耗额度。";
    }

    @Override
    public String argsHint() {
        return "{\"action\": \"list|stats|recurring，默认 list\", "
                + "\"hours\": \"list 的时间窗小时数，默认 24\", "
                + "\"failedOnly\": \"list 是否只看失败链路，默认 false\", "
                + "\"limit\": \"返回条数上限，默认 20\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        String action = str(args.get("action"));
        int limit = intOf(args.get("limit"), DEFAULT_LIMIT);
        return switch (action == null ? "list" : action.toLowerCase()) {
            case "stats" -> stats();
            case "recurring", "regression", "repeat" -> recurring(limit);
            default -> list(intOf(args.get("hours"), hoursFrom(userCommand)), boolOf(args.get("failedOnly")), limit);
        };
    }

    private ToolResult list(int hours, boolean failedOnly, int limit) {
        List<TraceAnalysisStore.AnalysisRecord> records = store.listSince(hours, failedOnly, limit);
        List<String> lines = new ArrayList<>();
        if (records.isEmpty()) {
            return ToolResult.note("过去 " + hours + " 小时内没有链路分析记录"
                    + (failedOnly ? "（仅统计失败链路）" : "")
                    + "。若确实发生过故障但没有记录，说明当时没有走链路分析，可以给出 trace ID 现查现分析。");
        }
        long failed = records.stream().filter(TraceAnalysisStore.AnalysisRecord::failed).count();
        List<Map<String, Object>> items = new ArrayList<>();
        for (TraceAnalysisStore.AnalysisRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId", r.traceId());
            m.put("analyzedAt", r.analyzedAt());
            m.put("failed", r.failed());
            m.put("services", r.services());
            m.put("failurePoint", r.failurePoint());
            m.put("fingerprint", r.signature());
            m.put("env", r.env());
            m.put("kbCaseId", r.kbCaseId());
            items.add(m);

            StringBuilder line = new StringBuilder();
            line.append(r.analyzedAt()).append("　").append(r.failed() ? "❌ 失败" : "✅ 成功");
            if (!r.services().isEmpty()) {
                line.append("　").append(String.join("/", r.services()));
            }
            if (r.failed() && !r.failurePoint().isBlank()) {
                line.append("　失败点：").append(r.failurePoint());
            }
            if (!r.signature().isBlank()) {
                line.append("　指纹：").append(r.signature());
            }
            if (!r.kbCaseId().isBlank()) {
                line.append("　命中案例 ").append(r.kbCaseId());
            }
            line.append("　trace ").append(r.traceId());
            lines.add(line.toString());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hours", hours);
        result.put("total", records.size());
        result.put("failed", failed);
        result.put("records", items);
        String summary = "过去 " + hours + " 小时共 " + records.size() + " 条链路分析记录，其中失败 " + failed + " 条";
        return new ToolResult("list", result, lines, summary);
    }

    private ToolResult stats() {
        TraceAnalysisStore.Stats s = store.stats();
        List<String> lines = new ArrayList<>();
        lines.add("累计分析链路：" + s.total() + " 条（失败 " + s.failed() + " 条，未查到 " + s.notFound()
                + " 条，命中案例 " + s.withCase() + " 条，涉及 span " + s.totalSpans() + " 个）");
        if (!s.topServices().isEmpty()) {
            lines.add("高频服务：" + join(s.topServices()));
        }
        if (!s.topSignatures().isEmpty()) {
            lines.add("高频故障指纹：" + join(s.topSignatures()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", s.total());
        result.put("failed", s.failed());
        result.put("notFound", s.notFound());
        result.put("withCase", s.withCase());
        result.put("topServices", s.topServices());
        result.put("topSignatures", s.topSignatures());
        return new ToolResult("list", result, lines,
                "链路分析累计 " + s.total() + " 条，失败 " + s.failed() + " 条");
    }

    private ToolResult recurring(int limit) {
        List<Map<String, Object>> rows = store.recurringSignatures(2, limit);
        if (rows.isEmpty()) {
            return ToolResult.note("没有反复出现的故障指纹：目前每个故障模式都只出现过一次，尚未形成回归。");
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            lines.add("指纹「" + r.get("signature") + "」出现 " + r.get("count") + " 次"
                    + "，最近一次 " + r.get("lastAt")
                    + (String.valueOf(r.get("failurePoints")).isBlank() ? "" : "，涉及失败点：" + r.get("failurePoints")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recurring", rows);
        return new ToolResult("list", result, lines,
                "共有 " + rows.size() + " 个故障指纹反复出现，属于需要重点治理的回归类问题");
    }

    private static String join(List<Map<String, Object>> top) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> m : top) {
            parts.add(m.get("name") + "(" + m.get("count") + ")");
        }
        return String.join("、", parts);
    }

    /** 「最近 12 小时」这类中文表达直接从指令里取小时数，省得规划器换算 */
    private static int hoursFrom(String command) {
        if (command == null) {
            return DEFAULT_HOURS;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3})\\s*(?:个)?\\s*小时").matcher(command);
        if (m.find()) {
            try {
                return Math.max(1, Math.min(Integer.parseInt(m.group(1)), 24 * 30));
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_HOURS;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intOf(Object o, int fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean boolOf(Object o) {
        return o != null && Boolean.parseBoolean(String.valueOf(o).trim());
    }
}
