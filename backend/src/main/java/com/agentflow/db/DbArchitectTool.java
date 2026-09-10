package com.agentflow.db;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库透视工具（内置 db-architect）：连接配置由程序管理（工作台 → 数据库连接），
 * 每次调用动态生成临时 .env，支持 MySQL / Oracle / PostgreSQL / 达梦。
 */
@Component
public class DbArchitectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DbArchitectTool.class);
    private static final int MAX_LINES = 80;
    private static final int MAX_LINE_CHARS = 240;

    private final DbProfileStore store;
    private final DbScannerRunner runner;

    public DbArchitectTool(DbProfileStore store, DbScannerRunner runner) {
        this.store = store;
        this.runner = runner;
    }

    @Override
    public String name() {
        return "db.inspect";
    }

    /** 动态描述：标注当前默认连接与全部连接清单，引导规划器不点名时走默认 */
    @Override
    public String description() {
        List<DbProfile> profiles = store.list();
        if (profiles.isEmpty()) {
            return "数据库透视（当前未配置任何连接：请提示用户在「工作台 → 数据库连接」中添加后重试）";
        }
        String names = profiles.stream()
                .map(p -> p.name() + "（" + p.type() + "，库 " + p.databases() + "）")
                .collect(Collectors.joining("；"));
        String active = store.getActive();
        return "数据库透视：查表结构/DDL/建表语法、按中文名找表、导出数据为 INSERT/表格、生成 ER 图、跨库结构比对、业务域分析"
                + "（只读查询）。已配置连接：" + names + "。用户指令未指明数据库时不要传 profile 参数"
                + (active == null ? "" : "（当前默认连接：" + active + "）")
                + "；明确提到某个连接/库时 profile 用对应连接名";
    }

    @Override
    public String argsHint() {
        return "{\"profile\": \"连接名（用户未指明数据库时省略，自动用默认连接）\", \"mode\": \"analyze|ddl|find-table|export-row|scan|compare|domain（默认 analyze：单表/多表/关键字聚焦分析）\", "
                + "\"db\": \"数据库名（profile 配多个库时选择）\", \"table\": \"目标表名\", \"tables\": \"多表逗号分隔\", "
                + "\"tableLike\": \"表名关键字\", \"tableCommentLike\": \"表中文名/注释关键字\", \"schema\": \"Schema（Oracle/PG/达梦）\", "
                + "\"where\": \"export-row 过滤条件（不带 WHERE）\", \"filterColumn\": \"等值过滤字段\", \"filterValue\": \"等值过滤值\", "
                + "\"limit\": \"行数限制\", \"format\": \"insert|table|json|csv\", "
                + "\"rightProfile\": \"compare 右侧连接\", \"rightDb\": \"compare 右侧库\", \"leftDb\": \"compare 左侧库\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        List<DbProfile> profiles = store.list();
        if (profiles.isEmpty()) {
            return ToolResult.note("还没有配置数据库连接：请先在「工作台 → 数据库连接」中添加连接，再执行数据库类任务");
        }
        // profile 解析优先级：显式参数 > 界面设定的默认连接 > 唯一连接
        String profileName = str(args.get("profile"));
        if (profileName == null) {
            profileName = store.getActive();
        }
        if (profileName == null && profiles.size() == 1) {
            profileName = profiles.get(0).name();
        }
        if (profileName == null) {
            return ToolResult.note("配置了多个连接且未指定默认：请在指令中指明用哪个（" + profileNames(profiles)
                    + "），或在「工作台 → 数据库连接 / 输入区选择器」中设定默认连接");
        }
        if (store.find(profileName) == null) {
            return ToolResult.note("连接「" + profileName + "」不存在，可用连接：" + profileNames(profiles));
        }

        String mode = resolveMode(str(args.get("mode")), userCommand);
        List<String> cmd = new ArrayList<>();
        cmd.add(mode);
        addFlag(cmd, "--profile", profileName);
        addFlag(cmd, "--db", str(args.get("db")));
        addFlag(cmd, "--table", str(args.get("table")));
        addFlag(cmd, "--tables", str(args.get("tables")));
        addFlag(cmd, "--table-like", str(args.get("tableLike")));
        addFlag(cmd, "--table-prefix", str(args.get("tablePrefix")));
        addFlag(cmd, "--table-comment-like", str(args.get("tableCommentLike")));
        addFlag(cmd, "--schema", str(args.get("schema")));
        addFlag(cmd, "--where", str(args.get("where")));
        addFlag(cmd, "--filter-column", str(args.get("filterColumn")));
        addFlag(cmd, "--filter-value", str(args.get("filterValue")));
        addFlag(cmd, "--limit", str(args.get("limit")));
        addFlag(cmd, "--format", str(args.get("format")));
        addFlag(cmd, "--left-db", str(args.get("leftDb")));
        addFlag(cmd, "--right-profile", str(args.get("rightProfile")));
        addFlag(cmd, "--right-db", str(args.get("rightDb")));
        if ("scan".equals(mode)) {
            cmd.add("--max-tables");
            cmd.add("30");
        }

        DbScannerRunner.ScanResult result = runner.run(cmd, profileName);
        if (!result.ok()) {
            return ToolResult.note("db.inspect " + mode + " 失败（exit " + result.exitCode() + "）：\n"
                    + truncate(result.output(), 1500));
        }
        // 未直接命中（表名/关键字拼错等）：拉表清单做模糊匹配，给出候选选项
        String keyword = firstNonBlank(str(args.get("table")), str(args.get("tables")),
                str(args.get("tableLike")), str(args.get("tableCommentLike")));
        if (keyword != null && isNoHit(mode, result)) {
            ToolResult.Clarify clarify = buildClarify(profileName, keyword, str(args.get("profile")) != null);
            if (clarify != null) {
                return ToolResult.withClarify(
                        "没有找到与「" + keyword + "」直接匹配的表，已给出最相近的候选，请选择或修改关键词",
                        clarify);
            }
        }
        List<String> lines = new ArrayList<>();
        for (String l : result.reportText().split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) {
                lines.add(truncate(t, MAX_LINE_CHARS));
            }
        }
        for (String l : result.output().split("\n")) {
            String t = l.trim();
            if (!t.isEmpty() && !t.startsWith("正在连接")) {
                lines.add(truncate(t, MAX_LINE_CHARS));
            }
            if (lines.size() >= MAX_LINES) {
                lines.add("…（输出过长已截断）");
                break;
            }
        }
        if (lines.isEmpty()) {
            lines.add("（无输出）");
        }
        return new ToolResult("list", null, lines,
                "数据库透视 " + mode + " 完成 · 连接 " + profileName);
    }

    /* ================= 未命中候选（拼写纠错式交互） ================= */

    /** 判定"没有直接命中"：find-table 零匹配；analyze 指定表不存在（报告 0 表）；ddl 表不存在时静默输出空 DDL */
    private static boolean isNoHit(String mode, DbScannerRunner.ScanResult result) {
        String out = result.output();
        if (out.contains("未找到匹配表")) {
            return true;
        }
        if ("ddl".equals(mode)) {
            return !out.contains("CREATE TABLE");
        }
        return out.contains("以下表未找到") && result.reportText().contains("表数量：`0`");
    }

    /**
     * 拉取表清单（table-like "_" 可匹配几乎所有下划线命名表），与关键词模糊匹配取 top 4：
     * 子串命中（表名/注释）强优先，其次编辑距离相似度。
     */
    private ToolResult.Clarify buildClarify(String profileName, String keyword, boolean explicitProfile) {
        try {
            DbScannerRunner.ScanResult r = runner.run(
                    List.of("find-table", "--profile", profileName, "--table-like", "_"), profileName);
            if (!r.ok()) {
                return null;
            }
            List<String[]> tables = new ArrayList<>();
            for (String line : r.output().split("\n")) {
                // find-table 输出 markdown 表：| 表名 | 中文名/注释 |
                java.util.regex.Matcher m = TABLE_ROW.matcher(line.trim());
                if (m.matches()) {
                    String comment = m.group(2);
                    tables.add(new String[]{m.group(1), "-".equals(comment) ? "" : comment});
                }
                if (tables.size() >= 500) {
                    break;
                }
            }
            if (tables.isEmpty()) {
                return null;
            }
            String kw = keyword.toLowerCase().replaceAll("[\\s_]", "");
            record Candidate(String name, String comment, int score) {
            }
            List<Candidate> best = new ArrayList<>();
            for (String[] t : tables) {
                String name = t[0];
                String comment = t[1];
                String normName = name.toLowerCase().replaceAll("[\\s_]", "");
                int score;
                if (normName.contains(kw) || (!comment.isEmpty() && comment.contains(keyword))) {
                    score = 100;
                } else {
                    int dist = levenshtein(normName, kw);
                    int maxLen = Math.max(normName.length(), kw.length());
                    score = maxLen == 0 ? 0 : (int) (80.0 * (maxLen - dist) / maxLen);
                }
                if (score >= 45) {
                    best.add(new Candidate(name, comment, score));
                }
            }
            best.sort((a, b) -> b.score() - a.score());
            if (best.isEmpty()) {
                return null;
            }
            List<Map<String, String>> options = new ArrayList<>();
            for (Candidate c : best.subList(0, Math.min(4, best.size()))) {
                String label = c.comment().isEmpty() ? c.name() : c.name() + "（" + c.comment() + "）";
                String action = "查 " + c.name() + " 表的详细结构"
                        + (explicitProfile ? "（" + profileName + "）" : "");
                options.add(Map.of("label", label, "action", action));
            }
            String question = "没有找到与「" + keyword + "」直接匹配的表，你想找的是不是：";
            return new ToolResult.Clarify(question, options);
        } catch (Exception ex) {
            log.warn("生成候选失败: {}", ex.getMessage());
            return null;
        }
    }

    private static final java.util.regex.Pattern TABLE_ROW =
            java.util.regex.Pattern.compile("\\|\\s*([A-Za-z0-9_$.]+)\\s*\\|\\s*([^|]*?)\\s*\\|");

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 模式解析：显式参数优先，其次从指令关键词推断，默认 analyze */
    private String resolveMode(String mode, String command) {        if (mode != null && !mode.isBlank()) {
            return mode.trim().toLowerCase();
        }
        String c = command == null ? "" : command;
        if (c.contains("建表语法") || c.toLowerCase().contains("ddl")) {
            return "ddl";
        }
        if (c.contains("导出") || c.contains("insert") || c.contains("数据")) {
            return "export-row";
        }
        if (c.contains("对比") || c.contains("比对") || c.contains("差异")) {
            return "compare";
        }
        if (c.contains("业务域")) {
            return "domain";
        }
        if (c.contains("找表") || c.contains("哪些表") || c.contains("表清单")) {
            return "find-table";
        }
        if (c.contains("全库") || c.contains("整体扫描") || c.contains("梳理")) {
            return "scan";
        }
        return "analyze";
    }

    private void addFlag(List<String> cmd, String flag, String value) {
        if (value != null && !value.isBlank()) {
            cmd.add(flag);
            cmd.add(value);
        }
    }

    private static String profileNames(List<DbProfile> profiles) {
        return profiles.stream().map(DbProfile::name).collect(Collectors.joining("、"));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
