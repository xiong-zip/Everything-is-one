package com.agentflow.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.LocalDate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * wecom 支撑逻辑的纯函数测试：不依赖本机安装 wecom-cli。
 */
class WecomSupportTest {

    /* ---------- 授权判定：整行严格比较 ---------- */

    @Test
    void authorizedStrictAcceptsExactLine() {
        assertTrue(WecomCliRunner.authorizedStrict("authorized"));
        assertTrue(WecomCliRunner.authorizedStrict("  authorized  \n"));
    }

    @Test
    void authorizedStrictRejectsSubstring() {
        // unauthorized 也包含 "authorized" 子串，绝不能按 contains 判定
        assertFalse(WecomCliRunner.authorizedStrict("unauthorized"));
        assertFalse(WecomCliRunner.authorizedStrict(""));
        assertFalse(WecomCliRunner.authorizedStrict(null));
        assertFalse(WecomCliRunner.authorizedStrict("some warning\nauthorized-pending"));
    }

    /* ---------- wecom.call 命令拼装 ---------- */

    @Test
    void buildCommandSimpleMethod() {
        assertEquals(List.of("todo", "add"), WecomCallTool.buildCommand("todo", "add"));
    }

    @Test
    void buildCommandResourceMethod() {
        assertEquals(List.of("doc", "contents", "get"), WecomCallTool.buildCommand("doc", "contents.get"));
    }

    @Test
    void buildCommandRejectsUnsafeTokens() {
        assertNull(WecomCallTool.buildCommand("doc", "--help"));
        assertNull(WecomCallTool.buildCommand("--help", "x"));
        assertNull(WecomCallTool.buildCommand("doc", "contents."));
        assertNull(WecomCallTool.buildCommand("doc", ".get"));
        assertNull(WecomCallTool.buildCommand("doc", "List")); // 大写不允许
        assertNull(WecomCallTool.buildCommand("doc search", "x")); // 空格不允许
    }

    /* ---------- 关键词解析 ---------- */

    @Test
    void keywordsParsesStringAndList() {
        assertEquals(List.of("周报", "故障"), WecomDocQueryTool.keywords("周报 故障"));
        assertEquals(List.of("周报", "故障"), WecomDocQueryTool.keywords("周报，故障"));
        assertEquals(List.of("a", "b"), WecomDocQueryTool.keywords(List.of("a", " b ", "")));
        assertTrue(WecomDocQueryTool.keywords(null).isEmpty());
    }

    /* ---------- help 输出解析 ---------- */

    private static final String DOC_HELP = """
            文档服务，提供文档的创建、导入、列表、搜索等能力

            Usage: wecom-cli doc [OPTIONS] [COMMAND]

            Commands:
              create    新建文档，支持 doc/sheet/smartsheet 文档类型
              import    创建文档导入任务
              contents  管理 'contents' 资源
              help      Print help

            Options:
              --doc
                  显示服务文档
            """;

    @Test
    void parseCommandsExtractsSection() {
        List<String[]> cmds = WecomToolService.parseCommands(DOC_HELP);
        assertEquals(4, cmds.size());
        assertEquals("create", cmds.get(0)[0]);
        assertTrue(cmds.get(0)[1].startsWith("新建文档"));
        assertEquals("contents", cmds.get(2)[0]);
    }

    @Test
    void parseCommandsStopsAtNonIndentedSection() {
        // Options: 区段出现在 Commands: 之后，标题行不再缩进，解析应停止
        List<String[]> cmds = WecomToolService.parseCommands(DOC_HELP);
        assertTrue(cmds.stream().noneMatch(c -> c[0].equals("doc")));
    }

    @Test
    void resourceCommandDetectedByDescription() {
        assertTrue(WecomToolService.isResourceCommand("管理 'contents' 资源"));
        assertFalse(WecomToolService.isResourceCommand("新建文档"));
        assertFalse(WecomToolService.isResourceCommand(null));
    }

    @Test
    void parseCommandsHandlesBlankInput() {
        assertTrue(WecomToolService.parseCommands(null).isEmpty());
        assertTrue(WecomToolService.parseCommands("no commands here").isEmpty());
    }

    /* ---------- 占位符防御：规划器可能把参数提示当值传 ---------- */

    @Test
    void placeholderValuesDetected() {
        assertTrue(WecomToolSupport.isPlaceholderValue("<上一步定位到的文档ID或URL>"));
        assertTrue(WecomToolSupport.isPlaceholderValue("上一步定位到的文档"));
        assertTrue(WecomToolSupport.isPlaceholderValue(""));
        assertTrue(WecomToolSupport.isPlaceholderValue(null));
        assertFalse(WecomToolSupport.isPlaceholderValue("w3_AFsALXg6AP8CNDBEtlxbLRh6X6DaT_a"));
        assertFalse(WecomToolSupport.isPlaceholderValue("https://doc.weixin.qq.com/doc/w3_xxx"));
    }

    /* ---------- wecom.daily：日期解析 / 摘要构建 / 幂等匹配 ---------- */

    @Test
    void dailyResolvesDateWords() {
        LocalDate today = LocalDate.of(2026, 9, 24);
        assertEquals("2026-09-24", WecomDailyTool.resolveDate(null, today));
        assertEquals("2026-09-24", WecomDailyTool.resolveDate("today", today));
        assertEquals("2026-09-23", WecomDailyTool.resolveDate("yesterday", today));
        assertEquals("2026-09-20", WecomDailyTool.resolveDate("2026-09-20", today));
        assertEquals("2026-09-24", WecomDailyTool.resolveDate("不是日期", today)); // 非法回落今天
    }

    @Test
    void dailyBuildsNumberedSummary() {
        List<String> lines = List.of(
                "【agentflow-backend】2 个提交",
                "14:22 · 修复登录日志不落库问题 ｜ 详情：身份上下文为空导致 NPE",
                "09-24 16:05 · 优化登录日志身份获取方式");
        String s = WecomDailyTool.buildSummary(lines);
        assertEquals("1. 修复登录日志不落库问题 ｜ 详情：身份上下文为空导致 NPE\n2. 优化登录日志身份获取方式", s);
        // 无提交的占位场景
        assertEquals("1. 暂无提交记录", WecomDailyTool.buildSummary(List.of("暂无提交记录")));
    }

    @Test
    void dailyMatchesRecordByDateAndCreator() throws Exception {
        String human = """
                {"record_id":"r1","creator_name":"蔡锦诚","values":{"日报提交日期":"2026-09-24 00:00:00",
                 "提交人":[{"userId":"u1","userName":"蔡锦诚"}]}}""";
        String bot = """
                {"record_id":"r2","creator_name":"肖雄的机器人","values":{"日报提交日期":"2026-09-24 00:00:00"}}""";
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        JsonNode h = m.readTree(human);
        JsonNode b = m.readTree(bot);
        // 机器人创建的行：日期命中即为目标（自己的行，提交人字段对机器人只读）
        assertTrue(WecomDailyTool.matchesTarget(b, "2026-09-24", "", "日报提交日期", "提交人"));
        assertFalse(WecomDailyTool.matchesTarget(b, "2026-09-23", "", "日报提交日期", "提交人"));
        // 人类同事同一天的行：不能命中（哪怕未配置提交人，机器人只管自己的记录）
        assertFalse(WecomDailyTool.matchesTarget(h, "2026-09-24", "", "日报提交日期", "提交人"));
        assertFalse(WecomDailyTool.matchesTarget(h, "2026-09-24", "u9", "日报提交日期", "提交人"));
    }

    /* ---------- Windows 参数预转义 ---------- */

    @Test
    void escapeWinArgHandlesQuotesAndBackslashes() {
        // 字面双引号 → \"
        assertEquals("[{\\\"fields\\\":{\\\"a\\\":\\\"b c\\\"}}]",
                WecomCliRunner.escapeWinArg("[{\"fields\":{\"a\":\"b c\"}}]"));
        // 引号前的反斜杠翻倍：n 个反斜杠 + 引号 → 2n+1 个反斜杠 + 引号
        assertEquals("a\\\\\\\"b", WecomCliRunner.escapeWinArg("a\\\"b"));
        // 普通反斜杠原样、串尾反斜杠翻倍（后面跟 Java 加的收尾引号）
        assertEquals("a\\b", WecomCliRunner.escapeWinArg("a\\b"));
        assertEquals("a\\\\", WecomCliRunner.escapeWinArg("a\\"));
        assertEquals("plain text", WecomCliRunner.escapeWinArg("plain text"));
    }

    /* ---------- wecom.call 的 args 解析 ---------- */

    @Test
    void parseArgsAcceptsMapAndJsonString() {
        assertEquals(Map.of("docid", "x"), WecomCallTool.parseArgs(Map.of("docid", "x")));
        assertEquals(Map.of("docid", "x", "limit", 3),
                WecomCallTool.parseArgs("{\"docid\":\"x\",\"limit\":3}"));
        // 规划器实测传过空字符串；空/坏输入按空对象处理
        assertTrue(WecomCallTool.parseArgs("").isEmpty());
        assertTrue(WecomCallTool.parseArgs(null).isEmpty());
        assertTrue(WecomCallTool.parseArgs("not json").isEmpty());
    }

    @Test
    void extractDocidFromCommandText() {
        assertEquals("s3_ASYAKwYsAF0CNcFyzqMudSrefo145",
                WecomCallTool.extractDocid("查询智能表格 docid=s3_ASYAKwYsAF0CNcFyzqMudSrefo145 的结构"));
        assertEquals("w3_AFsALXg6AP8CNDBEtlxbLRh6X6DaT_a",
                WecomCallTool.extractDocid("读取 https://doc.weixin.qq.com/doc/w3_AFsALXg6AP8CNDBEtlxbLRh6X6DaT_a?scode=x 全文"));
        assertNull(WecomCallTool.extractDocid("查询底座研发组日报"));
        assertNull(WecomCallTool.extractDocid(null));
    }

    /* ---------- 可执行文件解析 ---------- */

    @Test
    void findExecutableResolvesNpmPlatformBinary(@TempDir java.nio.file.Path dir) throws Exception {
        // 模拟 npm 全局目录：只有嵌套的平台二进制，PATH 裸名命中的是 shim（不是可执行文件）
        java.nio.file.Path bin = dir.resolve("node_modules").resolve("@wecom").resolve("cli")
                .resolve("node_modules").resolve("@wecom").resolve("cli-win32-x64").resolve("bin");
        java.nio.file.Files.createDirectories(bin);
        java.nio.file.Path exe = bin.resolve("wecom-cli.exe");
        java.nio.file.Files.writeString(exe, "fake");
        assertEquals(exe.toString(), WecomCliRunner.findExecutable("wecom-cli", java.util.List.of(dir)));
    }

    @Test
    void findExecutableDirectPathAndMissing(@TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path exe = dir.resolve("wecom-cli.exe");
        java.nio.file.Files.writeString(exe, "fake");
        // 显式路径：存在即用
        assertEquals(exe.toString(), WecomCliRunner.findExecutable(exe.toString(), java.util.List.of()));
        // 显式路径不存在：返回 null（如实报告 cliMissing，不猜测）
        assertNull(WecomCliRunner.findExecutable("C:/no/such/wecom-cli.exe", java.util.List.of()));
        // PATH 上什么都没有（用空目录验证，不能复用上面建过 exe 的目录）
        java.nio.file.Path empty = java.nio.file.Files.createDirectories(dir.resolve("empty"));
        assertNull(WecomCliRunner.findExecutable("wecom-cli", java.util.List.of(empty)));
    }

    /* ---------- guard：未启用时给出引导 ---------- */

    @Test
    void guardNotifiesWhenDisabled() {
        WecomCliRunner cli = new WecomCliRunner("wecom-cli", false, 60000, 60000);
        WecomDocQueryTool tool = new WecomDocQueryTool(cli);
        var r = tool.execute(Map.of("mode", "search", "keywords", "x"), "搜文档");
        assertNotNull(r);
        assertTrue(r.summary().contains("未启用"));
    }
}
