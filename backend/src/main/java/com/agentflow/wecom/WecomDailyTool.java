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

/**
 * 一键工作日报：拉取指定日期的 GitLab 提交 → 生成日报 → 写入团队智能表格。
 * 整条链在<b>一个工具内固化</b>，不依赖规划器把「生成 + 写入」两个意图都排进计划
 * （实测多意图指令下 LLM 会丢步骤），是定时无人值守的先决条件。
 *
 * <p>安全边界（这是本工程里唯一免确认的写工具）：目标表格、子表、提交人全部固定在
 * 配置里，LLM 只能控制日期与正文；写入<b>幂等</b>——同日同提交人的记录已存在则更新
 * 而非新增，定时任务重跑不会刷出重复行。
 */
@Component
public class WecomDailyTool extends WecomToolSupport {

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final GitLabTool gitLab;
    private final String docid;
    private final String sheetId;
    private final String submitterUserId;
    private final String summaryField;
    private final String planField;
    private final String dateField;
    private final String submitterField;

    public WecomDailyTool(WecomCliRunner cli,
                          GitLabTool gitLab,
                          @Value("${agentflow.wecom.daily.docid:}") String docid,
                          @Value("${agentflow.wecom.daily.sheet-id:}") String sheetId,
                          @Value("${agentflow.wecom.daily.submitter-userid:}") String submitterUserId,
                          @Value("${agentflow.wecom.daily.summary-field:今日工作总结}") String summaryField,
                          @Value("${agentflow.wecom.daily.plan-field:明日工作计划}") String planField,
                          @Value("${agentflow.wecom.daily.date-field:日报提交日期}") String dateField,
                          @Value("${agentflow.wecom.daily.submitter-field:提交人}") String submitterField) {
        super(cli);
        this.gitLab = gitLab;
        this.docid = nz(docid);
        this.sheetId = nz(sheetId);
        this.submitterUserId = nz(submitterUserId);
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
                + "——同日已有本提交人的记录则<b>更新</b>而非新增（幂等，定时重跑安全）；可选 plan 参数填明日工作计划；"
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
        String summary = buildSummary(lines);

        String existing = findExistingRecord(date);
        Map<String, Object> values = buildValues(summary, plan, date);
        if (existing != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("docid", docid);
            body.put("sheet_id", sheetId);
            body.put("records", List.of(Map.of("record_id", existing, "values", values)));
            ToolResult r = runJson(List.of("smartsheet", "records", "update"), body);
            if (r.result() != null && r.result().containsKey("note")) {
                return r;
            }
            return ToolResult.note("已更新 " + date + " 的日报记录（record_id " + existing + "）：" + summary);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docid", docid);
        body.put("sheet_id", sheetId);
        body.put("records", List.of(Map.of("values", values)));
        ToolResult r = runJson(List.of("smartsheet", "records", "add"), body);
        if (r.result() != null && r.result().containsKey("note")) {
            return r;
        }
        Object url = r.result() == null ? "" : r.result().get("url");
        return ToolResult.note("已写入 " + date + " 的日报记录" + (url == null || String.valueOf(url).isEmpty()
                ? "" : "：" + url) + "\n内容：" + summary);
    }

    /** 组装行记录 values：文本字段给 [{text:...}]（CLI 约定），日期给字符串；提交人 user 字段对机器人只读，不写 */
    private Map<String, Object> buildValues(String summary, String plan, String date) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(summaryField, List.of(Map.of("text", summary)));
        if (plan != null) {
            values.put(planField, List.of(Map.of("text", plan)));
        }
        values.put(dateField, date);
        return values;
    }

    /** 全表拉取后按「日期 + 提交人（如配置）」找已有记录，返回 record_id（幂等的关键） */
    private String findExistingRecord(String date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docid", docid);
        body.put("sheet_id", sheetId);
        body.put("limit", 1000);
        // 全表可能有几百条长文本记录，默认 60K 截断会砍断 JSON：这里放宽到 2MB
        WecomCliRunner.CliResult r = cli.exec(
                List.of("smartsheet", "records", "list", "--json", toJson(body)), 90_000, 2_000_000);
        if (!r.ok()) {
            return null;
        }
        try {
            JsonNode records = MAPPER.readTree(r.output()).path("records");
            if (!records.isArray()) {
                return null;
            }
            for (JsonNode rec : records) {
                if (matchesTarget(rec, date, submitterUserId, dateField, submitterField)) {
                    return rec.path("record_id").asText(null);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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

    /**
     * 提交清单 → 今日工作总结：去掉「HH:mm · 」/「MM-dd HH:mm · 」时间前缀，保留标题与详情；
     * 跳过「【项目】N 个提交」的分组头（纯函数，单测覆盖）。
     */
    static String buildSummary(List<String> lines) {
        List<String> out = new ArrayList<>();
        int i = 1;
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty() || t.startsWith("【")) {
                continue;
            }
            t = TIME_PREFIX.matcher(t).replaceFirst("");
            out.add(i++ + ". " + t);
        }
        return String.join("\n", out);
    }

    private static final java.util.regex.Pattern TIME_PREFIX =
            java.util.regex.Pattern.compile("^\\d{1,2}-\\d{1,2} \\d{1,2}:\\d{2} · |^\\d{1,2}:\\d{2} · ");

    /**
     * 记录是否为「目标日期 + 本工具写入」的那条。
     * 判据：日期匹配，且（提交人 userId 命中，或记录创建者是机器人）。
     * 实测 user 类型字段（提交人）对机器人身份只读——写入会被静默丢弃，
     * 所以「creator_name 含“机器人”」才是识别自己行的可靠方式（人类成员的创建者名不会含它）。
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
