package com.agentflow.tool;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 变更关联纯逻辑：评分规则、时间解析、服务列表与显式映射解析 */
class ChangeCorrelationToolTest {

    /* ---------- 评分 ---------- */

    @Test
    void scoreRules() {
        // 距故障 4 小时 + 修复类标题 + 失败点服务 + 流水线失败 = 3+2+2+1 = 8
        List<String> reasons = new ArrayList<>();
        int score = ChangeCorrelationTool.scoreCommit(4 * 60L, "fix: 修复支付超时", true, true, reasons);
        assertEquals(8, score);
        assertEquals(List.of("距故障4小时", "修复/回滚类提交", "失败点服务", "窗口内流水线失败"), reasons);

        // 距故障 30 小时 + 普通标题 + 非失败点服务 = 1+1 = 2
        reasons = new ArrayList<>();
        assertEquals(2, ChangeCorrelationTool.scoreCommit(30 * 60L, "docs: 更新说明", false, false, reasons));
        assertEquals(List.of("距故障30小时", "涉及服务"), reasons);

        // 性能/结构变更只加 1；revert 属修复类加 2
        reasons = new ArrayList<>();
        ChangeCorrelationTool.scoreCommit(10 * 60L, "perf: 优化索引", false, false, reasons);
        assertTrue(reasons.contains("性能/结构变更"));
        reasons = new ArrayList<>();
        ChangeCorrelationTool.scoreCommit(10 * 60L, "Revert 上线", false, false, reasons);
        assertTrue(reasons.contains("修复/回滚类提交"));

        // 超过 48 小时不再加时间分，但理由仍记录
        reasons = new ArrayList<>();
        ChangeCorrelationTool.scoreCommit(72 * 60L, "feat: 新功能", false, false, reasons);
        assertEquals(1, ChangeCorrelationTool.scoreCommit(72 * 60L, "feat: 新功能", false, false, new ArrayList<>()));
        assertEquals("距故障72小时", reasons.get(0));

        // 分钟级显示
        reasons = new ArrayList<>();
        ChangeCorrelationTool.scoreCommit(20L, "x", false, false, reasons);
        assertEquals("距故障20分钟", reasons.get(0));
    }

    /* ---------- 时间参数 ---------- */

    @Test
    void parseTimeArgSupportsCommonFormats() {
        OffsetDateTime t1 = ChangeCorrelationTool.parseTimeArg("2026-09-15 14:30");
        assertEquals("2026-09-15T14:30", t1.toLocalDateTime().toString());

        OffsetDateTime t2 = ChangeCorrelationTool.parseTimeArg("2026-09-15 14:30:05");
        assertEquals(5, t2.getSecond());

        OffsetDateTime t3 = ChangeCorrelationTool.parseTimeArg("2026-09-15");
        assertEquals(0, t3.getHour());

        OffsetDateTime t4 = ChangeCorrelationTool.parseTimeArg("2026-09-15T14:30:05+08:00");
        assertEquals("2026-09-15T14:30:05+08:00",
                t4.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

        assertNull(ChangeCorrelationTool.parseTimeArg(null));
        assertNull(ChangeCorrelationTool.parseTimeArg("上周吧"));
    }

    /* ---------- 服务与映射解析 ---------- */

    @Test
    void splitServicesDedupesInOrder() {
        assertEquals(List.of("pay-service", "order-service"),
                ChangeCorrelationTool.splitServices("pay-service, order-service、pay-service"));
        assertEquals(List.of("pay-service"),
                ChangeCorrelationTool.splitServices(" pay-service "));
        assertTrue(ChangeCorrelationTool.splitServices(null).isEmpty());
        assertTrue(ChangeCorrelationTool.splitServices(" , 、").isEmpty());
    }

    @Test
    void parseMappingsFromArgAndCommand() {
        Map<String, String> m = ChangeCorrelationTool.parseMappings("pay-service=middle/pay, order=core/order-service", null);
        assertEquals("middle/pay", m.get("pay-service"));
        assertEquals("core/order-service", m.get("order"));

        // 指令里的「映射 svc=group/proj」也应被识别（澄清卡点选重跑的落点）
        Map<String, String> fromCmd = ChangeCorrelationTool.parseMappings(null,
                "变更关联 traceId 123 映射 pay-service=middle/pay-service");
        assertEquals("middle/pay-service", fromCmd.get("pay-service"));

        // 不带斜杠的 key=value（如 hours=48）不构成映射
        Map<String, String> none = ChangeCorrelationTool.parseMappings("hours=48", "回溯 hours=72");
        assertTrue(none.isEmpty());
    }

    /* ---------- 工具元信息 ---------- */

    @Test
    void toolNameAndReadonly() {
        ChangeCorrelationTool tool = newTool();
        assertEquals("gitlab.changes", tool.name());
        assertEquals(false, tool.requiresConfirm(), "只读工具不应要求人工放行");
        assertNotNull(tool.argsHint());
    }

    private static ChangeCorrelationTool newTool() {
        GitLabTool gitLab = new GitLabTool(new ToolHttpClient(),
                new GitLabAccountStore("./target/test-gitlab-" + System.nanoTime() + ".db"),
                "http://gitlab.example.com", "");
        // 元信息测试不触发真实 HTTP，映射存储与 SigNoz 依赖可为 null
        return new ChangeCorrelationTool(gitLab, null, null, null, 48, 5, 10);
    }
}
