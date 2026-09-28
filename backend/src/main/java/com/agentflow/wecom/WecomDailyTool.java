package com.agentflow.wecom;

import com.agentflow.tool.GitLabTool;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一键工作日报：拉取指定日期的 GitLab 提交 → 生成日报 → 写入团队智能表格。
 * 整条链在<b>一个工具内固化</b>，不依赖规划器把「生成 + 写入」两个意图都排进计划
 * （实测多意图指令下 LLM 会丢步骤），是定时无人值守的先决条件。
 *
 * <p>安全边界（这是本工程里唯一免确认的写工具）：目标表格、子表、提交人全部固定在
 * 配置里，LLM 只能控制日期与正文；写入<b>幂等</b>——同日的机器人记录已存在则更新
 * 而非新增，定时任务重跑不会刷出重复行。
 *
 * <p>提交人补写（实测经验，纠正了早期「user 字段对机器人只读」的认知）：user 字段在
 * records add 时会被静默丢弃，但 add 之后再 update 一次即可写入。表格视图按「提交人」
 * 分组，不补写的记录会落到末尾空分组。userid 优先取配置 AGENTFLOW_DAILY_SUBMITTER；
 * 未配则按姓名（AGENTFLOW_DAILY_SUBMITTER_NAME）从表格历史记录反查——新成员无历史
 * 记录时反查不到，汇报里给出引导。写入后回读验证：update 返回里的 userId 可能是
 * 另一种内部 id，以回读结果的 userName 为准。
 */
@Component
public class WecomDailyTool extends WecomToolSupport {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GitLabTool gitLab;
    private final String docid;
    private final String sheetId;
    private final String submitterUserId;
    private final String submitterName;
    private final String summaryField;
    private final String planField;
    private final String dateField;
    private final String submitterField;

    public WecomDailyTool(WecomCliRunner cli,
                          GitLabTool gitLab,
                          @Value("${agentflow.wecom.daily.docid:}") String docid,
                          @Value("${agentflow.wecom.daily.sheet-id:}") String sheetId,
                          @Value("${agentflow.wecom.daily.submitter-userid:}") String submitterUserId,
                          @Value("${agentflow.wecom.daily.submitter-name:}") String submitterName,
                          @Value("${agentflow.wecom.daily.summary-field:今日工作总结}") String summaryField,
                          @Value("${agentflow.wecom.daily.plan-field:明日工作计划}") String planField,
                          @Value("${agentflow.wecom.daily.date-field:日报提交日期}") String dateField,
                          @Value("${agentflow.wecom.daily.submitter-field:提交人}") String submitterField) {
        super(cli);
        this.gitLab = gitLab;
        this.docid = nz(docid);
        this.sheetId = nz(sheetId);
        this.submitterUserId = nz(submitterUserId);
        this.submitterName = nz(submitterName);
        this.summaryField = nz(summaryField);
        this.planField = nz(planField);
        this.dateField = nz(dateField);
        this.submitterField = nz(submitterField);
    }

    @Override
    public String name() {
        return "wecom.daily";
    }

    @Override
    public String description() {
        if (docid.isEmpty() || sheetId.isEmpty()) {
            return "一键工作日报（未配置：.env 设 AGENTFLOW_DAILY_DOCID 与 AGENTFLOW_DAILY_SHEET_ID 后重启启用）。"
                    + "拉取指定日期 GitLab 提交生成日报并写入团队智能表格，同日已有记录自动更新（幂等）";
        }
        return "一键工作日报：拉取指定日期（默认今天）的 GitLab 提交生成日报，写入团队智能表格《底座研发组日报》的「团队日报汇总」子表"
                + "——同日已有本提交人的记录则<b>更新</b>而非新增（幂等，定时重跑安全）；写完自动补写「提交人」并回读确认"
                + "（记录归入本人分组而不是末尾空分组）；可选 plan 参数填明日工作计划；"
                + "当日无提交默认不写入（force=true 可强制写一条「暂无提交」）";
    }

    @Override
    public String argsHint() {
        return "{\"date\": \"today|yesterday|yyyy-MM-dd（默认 today）\", "
                + "\"plan\": \"明日工作计划文本（可选，不填则该字段留空）\", \"force\": \"当日无提交也写入（默认不写）\"}";
    }

    /**
     * 免确认的依据：目标与提交人固定于配置、写入幂等（见类注释）。
     * 若未配置目标表格则什么都不会发生（guard 返回引导）。
     */
    @Override
    public boolean requiresConfirm() {
        return false;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        ToolResult g = guard();
        if (g != null) {
            return g;
        }
        if (docid.isEmpty() || sheetId.isEmpty()) {
            return ToolResult.note("wecom.daily 未配置目标表格：.env 设 AGENTFLOW_DAILY_DOCID（智能表格文档 ID）"
                    + " 与 AGENTFLOW_DAILY_SHEET_ID（子表 ID）后重启");
        }
        String date = resolveDate(args.get("date"), LocalDate.now());
        String plan = str(args.get("plan"));
        boolean force = Boolean.parseBoolean(String.valueOf(args.getOrDefault("force", "false")));

        ToolResult commits = gitLab.execute(
                Map.of("type", "mine", "since", date, "until", date), userCommand);
        List<String> lines = commits == null ? null : commits.list();
        if (lines == null || lines.isEmpty()) {
            if (!force) {
                return ToolResult.note(date + " 无 GitLab 提交记录，未写入日报（需要空记录可加 force=true）");
            }
            lines = List.of("暂无提交记录");
        }
        // 表格版只留主干（实测长度刚好），细节括注留在对话汇报的完整版里
        String sheetSummary = buildSheetSummary(lines);
        String fullSummary = buildSummary(lines);

        // 全表拉一次复用两处：同日判重 + 按姓名反查提交人 userid
        JsonNode records = fetchRecords();
        String existing = findTargetRecord(records, date, submitterUserId, dateField, submitterField);
        String userId = !submitterUserId.isEmpty() ? submitterUserId
                : lookupUserIdByName(records, submitterName, submitterField);

        String recordId = null;
        boolean updated = false;
        if (existing != null) {
            Map<String, Object> body = updateBody(existing, buildValues(sheetSummary, plan, date, userId));
            ToolResult r = runJson(List.of("smartsheet", "records", "update"), body);
            String fail = errFail(r.result());
            if (fail != null) {
                return ToolResult.note("更新日报记录失败：" + fail);
            }
            if (ignoredRecordIds(r.result())) {
                existing = null; // 记录已被删，回落走新增
            } else {
                recordId = existing;
                updated = true;
            }
        }
        String url = "";
        if (existing == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("docid", docid);
            body.put("sheet_id", sheetId);
            body.put("records", List.of(Map.of("values", buildValues(sheetSummary, plan, date, null))));
            ToolResult r = runJson(List.of("smartsheet", "records", "add"), body);
            String fail = errFail(r.result());
            if (fail != null) {
                return ToolResult.note("写入日报记录失败：" + fail);
            }
            url = r.result().get("url") == null ? "" : String.valueOf(r.result().get("url"));
            recordId = extractRecordId(r.result());
            if (recordId == null) {
                return ToolResult.note("已写入但响应里没有 record_id，无法补写提交人，请到表格核对："
                        + (url.isEmpty() ? sheetSummary : url));
            }
            if (userId != null) {
                // 补写提交人：add 时 user 字段被静默丢弃，实测 add 后再 update 一次即可写入；
                // 失败不算整体失败（只影响分组归属），继续回读并在汇报里如实说明
                runJson(List.of("smartsheet", "records", "update"),
                        updateBody(recordId, submitterValues(userId)));
            }
        }

        String action = updated ? "已更新 " : "已写入 ";
        StringBuilder msg = new StringBuilder(action).append(date).append(" 的日报记录（record_id ")
                .append(recordId).append("）");
        msg.append(submitterReport(recordId, userId));
        msg.append("\n内容（表格内为主干版，完整细节如下）：\n").append(fullSummary);
        if (!url.isEmpty()) {
            msg.append("\n链接：").append(url);
        }
        return ToolResult.note(msg.toString());
    }

    /** 提交人一行的汇报：回读确认 / 反查失败引导 / 未配置时省略（纯函数，单测覆盖） */
    private String submitterReport(String recordId, String userId) {
        if (userId == null) {
            if (!submitterName.isEmpty()) {
                return "\n提交人：未能补写——表格历史记录里查不到姓名「" + submitterName
                        + "」，本条会落在末尾空分组；可让本人先在表格手动提交一条，"
                        + "或在 .env 配 AGENTFLOW_DAILY_SUBMITTER=<userid>";
            }
            return "";
        }
        JsonNode rec = findRecordById(fetchRecords(), recordId);
        String name = recordSubmitterName(rec, submitterField);
        return name.isEmpty()
                ? "\n提交人：补写后回读未确认（记录可能仍在末尾空分组），建议到表格复核"
                : "\n提交人：" + name + "（已回读确认，归入本人分组）";
    }

    /** 组装行记录 values：文本字段给 [{text:...}]（CLI 约定），日期给字符串；userId 非空时带上提交人 */
    private Map<String, Object> buildValues(String summary, String plan, String date, String userId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(summaryField, List.of(Map.of("text", summary)));
        if (plan != null) {
            values.put(planField, List.of(Map.of("text", plan)));
        }
        values.put(dateField, date);
        if (userId != null) {
            values.put(submitterField, List.of(Map.of("userId", userId)));
        }
        return values;
    }

    private Map<String, Object> submitterValues(String userId) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(submitterField, List.of(Map.of("userId", userId)));
        return values;
    }

    private Map<String, Object> updateBody(String recordId, Map<String, Object> values) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docid", docid);
        body.put("sheet_id", sheetId);
        body.put("records", List.of(Map.of("record_id", recordId, "values", values)));
        return body;
    }

    /**
     * 全表拉取（limit 1000）：判重与按姓名反查 userid 的共用数据源。
     * 全表可能有几百条长文本记录，默认 60K 截断会砍断 JSON：这里放宽到 2MB。
     */
    private JsonNode fetchRecords() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docid", docid);
        body.put("sheet_id", sheetId);
        body.put("limit", 1000);
        WecomCliRunner.CliResult r = cli.exec(
                List.of("smartsheet", "records", "list", "--json", toJson(body)), 90_000, 2_000_000);
        if (!r.ok()) {
            return null;
        }
        try {
            JsonNode records = MAPPER.readTree(r.output()).path("records");
            return records.isArray() ? records : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 按「日期 + 机器人自己（或提交人命中）」找已有记录，返回 record_id（幂等的关键） */
    static String findTargetRecord(JsonNode records, String date, String submitterUserId,
                                   String dateField, String submitterField) {
        if (records == null) {
            return null;
        }
        for (JsonNode rec : records) {
            if (matchesTarget(rec, date, submitterUserId, dateField, submitterField)) {
                return rec.path("record_id").asText(null);
            }
        }
        return null;
    }

    /** 按 record_id 精确找记录（回读验证用） */
    static JsonNode findRecordById(JsonNode records, String recordId) {
        if (records == null || recordId == null) {
            return null;
        }
        for (JsonNode rec : records) {
            if (recordId.equals(rec.path("record_id").asText(""))) {
                return rec;
            }
        }
        return null;
    }

    /** 按姓名从表格历史记录反查 userid：提交人.userName == 姓名的那条的 userId */
    static String lookupUserIdByName(JsonNode records, String name, String submitterField) {
        if (records == null || name == null || name.isEmpty()) {
            return null;
        }
        for (JsonNode rec : records) {
            JsonNode submitters = rec.path("values").path(submitterField);
            if (!submitters.isArray()) {
                continue;
            }
            for (JsonNode s : submitters) {
                if (name.equals(s.path("userName").asText(""))) {
                    String id = s.path("userId").asText("");
                    if (!id.isEmpty()) {
                        return id;
                    }
                }
            }
        }
        return null;
    }

    /** 记录的提交人姓名（回读验证取 userName，不比对 userId——update 返回的可能是内部 id） */
    static String recordSubmitterName(JsonNode record, String submitterField) {
        JsonNode submitters = record == null ? null : record.path("values").path(submitterField);
        if (submitters == null || !submitters.isArray() || submitters.size() == 0) {
            return "";
        }
        return submitters.get(0).path("userName").asText("");
    }

    /** 从 add 响应里取 record_id（补写提交人与回读验证都依赖它） */
    static String extractRecordId(Map<String, Object> result) {
        Object recs = result == null ? null : result.get("records");
        if (recs instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) {
            Object id = m.get("record_id");
            if (id != null && !String.valueOf(id).isEmpty()) {
                return String.valueOf(id);
            }
        }
        return null;
    }

    /**
     * 业务成败判定：CLI 退出码为 0 ≠ 成功，必须看 errcode（实测约定）。
     * 返回 null 表示成功，否则返回可直接展示的失败原因。
     */
    static String errFail(Map<String, Object> result) {
        if (result == null) {
            return "wecom-cli 无有效输出";
        }
        if (result.containsKey("note")) {
            return String.valueOf(result.get("note"));
        }
        Object ec = result.get("errcode");
        if (ec != null && !"0".equals(String.valueOf(ec))) {
            return "errcode " + ec + "：" + result.get("errmsg");
        }
        return null;
    }

    /** update 返回「已忽略 N 个不存在的 record_id」= 目标记录已被删，应回落走新增 */
    static boolean ignoredRecordIds(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        try {
            String s = MAPPER.writeValueAsString(result);
            return s.contains("已忽略") && s.contains("record_id");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 日期解析：today/yesterday/yyyy-MM-dd；非法值回落到今天（纯函数，单测覆盖） */
    static String resolveDate(Object raw, LocalDate today) {
        String v = raw == null ? null : String.valueOf(raw).trim();
        if (v == null || v.isEmpty() || "today".equalsIgnoreCase(v) || "今天".equals(v)) {
            return today.format(D);
        }
        if ("yesterday".equalsIgnoreCase(v) || "昨天".equals(v)) {
            return today.minusDays(1).format(D);
        }
        try {
            return LocalDate.parse(v).format(D);
        } catch (Exception ex) {
            return today.format(D);
        }
    }

    /** 提交清单 → 完整版总结（对话汇报用）：保留标题与「｜ 详情：…」细节（纯函数，单测覆盖） */
    static String buildSummary(List<String> lines) {
        return numbered(lines, false);
    }

    /** 提交清单 → 表格版总结：只留「编号 + 一句话主干」，剥掉详情尾缀与括号细节（纯函数，单测覆盖） */
    static String buildSheetSummary(List<String> lines) {
        return numbered(lines, true);
    }

    private static String numbered(List<String> lines, boolean stripDetails) {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith("【")) {
                continue;
            }
            t = TIME_PREFIX.matcher(t).replaceFirst("");
            if (stripDetails) {
                t = DETAIL_SUFFIX.matcher(t).replaceFirst("");
                t = BRACKETS.matcher(t).replaceAll("");
            }
            out.add(i++ + ". " + t);
        }
        return String.join("\n", out);
    }

    private static final Pattern TIME_PREFIX =
            Pattern.compile("^\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{2} · |^\\d{1,2}:\\d{2} · ");
    /** 「｜ 详情：…」到行尾的尾缀（GitLabTool 的行格式），表格版剥离 */
    private static final Pattern DETAIL_SUFFIX = Pattern.compile("\\s*｜\\s*详情：.*$");
    /** 全角/半角括号细节（如 conventional commit 的 scope、括注说明），表格版剥离 */
    private static final Pattern BRACKETS = Pattern.compile("（[^）]*）|\\([^()]*\\)");

    /**
     * 记录是否为「目标日期 + 本工具写入」的那条。
     * 判据：日期匹配，且（提交人 userId 命中，或记录创建者是机器人）。
     * 「creator_name 含“机器人”」是识别自己行的可靠方式（人类成员的创建者名不会含它）；
     * 提交人命中分支在补写提交人之后同样会命中自己旧行。
     */
    static boolean matchesTarget(JsonNode record, String date, String submitterUserId,
                                 String dateField, String submitterField) {
        JsonNode values = record.path("values");
        String recDate = values.path(dateField).asText("");
        if (!recDate.startsWith(date)) {
            return false;
        }
        if (record.path("creator_name").asText("").contains("机器人")) {
            return true;
        }
        if (submitterUserId == null || submitterUserId.isEmpty()) {
            return false; // 未配置提交人时也不认人类的行：机器人只管理自己创建的记录
        }
        JsonNode submitters = values.path(submitterField);
        if (!submitters.isArray()) {
            return false;
        }
        for (JsonNode s : submitters) {
            if (submitterUserId.equals(s.path("userId").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
