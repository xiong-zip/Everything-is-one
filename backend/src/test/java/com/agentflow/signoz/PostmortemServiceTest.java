package com.agentflow.signoz;

import com.agentflow.alarm.AlarmStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 故障报告的模板拼装与时间线（纯函数部分） */
class PostmortemServiceTest {

    private static AlarmStore.Record alarm(String receivedAt, String severity, String name,
                                           String service, String state, String summary) {
        return new AlarmStore.Record(1, receivedAt, "signoz", name, severity, service, "", "firing",
                "k", state, "task-1", summary, "", false, 1000);
    }

    /* ---------- buildTimeline ---------- */

    @Test
    void timelineSortedAcrossFormats() {
        List<AlarmStore.Record> alarms = List.of(
                alarm("2026-09-08 14:35:02", "critical", "支付错误率升高", "pay-service", "done", "数据库连接池耗尽"),
                alarm("2026-09-08 14:31:00", "warning", "慢查询增多", "pay-service", "done", "SQL 慢查询"));
        List<String> changes = List.of(
                "#1 [7分] abc1234 · 张三 · 09-08 14:30 · 上调连接池参数（距故障7分钟）");
        List<PostmortemService.TimelineItem> tl = PostmortemService.buildTimeline(
                "2026-09-08T14:32:10+08:00", alarms, changes);

        assertEquals(4, tl.size());
        // 顺序：嫌疑变更(14:30, 补当年) → 慢查询告警(14:31) → 链路发生(14:32) → 错误率告警(14:35)
        assertTrue(tl.get(0).time().endsWith("14:30"));
        assertEquals("2026-09-08 14:31:00", tl.get(1).time());
        assertEquals("2026-09-08 14:32", tl.get(2).time());
        assertEquals("2026-09-08 14:35:02", tl.get(3).time());
        assertTrue(tl.get(3).event().contains("数据库连接池耗尽"));
    }

    @Test
    void sortKeyPadsCurrentYearForShortDates() {
        String key = PostmortemService.sortKey("09-08 14:30");
        assertTrue(key.matches("\\d{4}-09-08 14:30"));
        // 完整时间原样返回，可直接与补齐后的比较
        assertEquals("2026-09-08 14:31:00", PostmortemService.sortKey("2026-09-08 14:31:00"));
    }

    /* ---------- renderTemplate ---------- */

    @Test
    void templateContainsAllSections() {
        PostmortemService.Evidence ev = new PostmortemService.Evidence(
                "c4ea16342cf1a0526d22fa20d57c9e2a", "2026-09-08 14:32", "dev",
                List.of("pay-service", "order-service"), true,
                "pay-service / PayDao.queryOrder", "TimeoutException", "PayDao.queryOrder 超时",
                "5820ms", null, "链路分析摘要正文",
                List.of("#1 [7分] abc1234 · 张三 · 09-08 14:30 · 上调连接池参数"),
                List.of("pay-service：异常实例 1 个", "⚠ pay-7f9d-x（dev）状态 Running · 就绪 0/1"),
                List.of(new PostmortemService.TimelineItem("2026-09-08 14:32", "链路发生")),
                List.of("2026-09-08 14:35:02 [critical] 支付错误率升高 → 值守已排查"));
        String md = PostmortemService.renderTemplate(ev);

        assertTrue(md.contains("## 影响面"));
        assertTrue(md.contains("## 时间线"));
        assertTrue(md.contains("## 根因分析"));
        assertTrue(md.contains("## 处置建议"));
        assertTrue(md.contains("## 待办事项"));
        assertTrue(md.contains("c4ea16342cf1a0526d22fa20d57c9e2a"));
        assertTrue(md.contains("pay-service"));
        assertTrue(md.contains("上调连接池参数"));
        assertTrue(md.contains("未配置 LLM"));
        // 未命中知识库时应引导人工确认而不是留空
        assertTrue(md.contains("知识库未命中"));
    }

    @Test
    void templateWithEmptyEvidenceFallsBackToPlaceholder() {
        PostmortemService.Evidence ev = new PostmortemService.Evidence(
                "abc", "2026-09-08 14:32", "", List.of("svc"), false,
                "", "", "", "?", null, "摘要",
                List.of(), List.of(), List.of(), List.of());
        String md = PostmortemService.renderTemplate(ev);
        assertTrue(md.contains("（无）"));  // 变更/告警缺失时的占位
        assertTrue(md.contains("成功链路"));
    }
}
