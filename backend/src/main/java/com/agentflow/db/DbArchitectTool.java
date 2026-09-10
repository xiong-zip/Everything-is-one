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

    /** 动态描述：把当前已配置的连接清单告诉规划器，未配置时引导用户去界面添加 */
    @Override
    public String description() {
        List<DbProfile> profiles = store.list();
        if (profiles.isEmpty()) {
            return "数据库透视（当前未配置任何连接：请提示用户在「工作台 → 数据库连接」中添加后重试）";
        }
        String names = profiles.stream()
                .map(p -> p.name() + "（" + p.type() + "，库 " + p.databases() + "）")
                .collect(Collectors.joining("；"));
        return "数据库透视：查表结构/DDL/建表语法、按中文名找表、导出数据为 INSERT/表格、生成 ER 图、跨库结构比对、业务域分析"
                + "（只读查询）。已配置连接：" + names + "。profile 必须用上述连接名之一";
    }

    @Override
    public String argsHint() {
        return "{\"profile\": \"连接名（已配置的 profile）\", \"mode\": \"analyze|ddl|find-table|export-row|scan|compare|domain（默认 analyze：单表/多表/关键字聚焦分析）\", "
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
        String profileName = str(args.get("profile"));
        if (profileName == null) {
            if (profiles.size() == 1) {
                profileName = profiles.get(0).name();
            } else {
                return ToolResult.note("配置了多个连接，请在指令中指明用哪个：" + profileNames(profiles));
            }
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

    /** 模式解析：显式参数优先，其次从指令关键词推断，默认 analyze */
    private String resolveMode(String mode, String command) {
        if (mode != null && !mode.isBlank()) {
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
