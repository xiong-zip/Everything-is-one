package com.agentflow.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 埋点：分组统计口径、失败计入、时间序列与上限裁剪 */
class LlmUsageStoreTest {

    @TempDir
    Path tmp;

    private LlmUsageStore store(int maxRows) {
        LlmUsageStore s = new LlmUsageStore(tmp.resolve("usage.db").toString(), maxRows);
        s.init();
        return s;
    }

    private static LlmUsageStore.LlmCall call(String purpose, String model, int prompt, int completion,
                                              long ms, boolean ok, boolean estimated, String taskId) {
        return new LlmUsageStore.LlmCall(purpose, "openai", model, prompt, completion,
                prompt + completion, estimated, ms, ok, ok ? "" : "超时", false, taskId);
    }

    @Test
    void totalsAggregateTokensLatencyAndFailures() {
        LlmUsageStore s = store(1000);
        s.record(call(LlmClient.PURPOSE_PLAN, "m1", 100, 50, 1000, true, false, "t1"));
        s.record(call(LlmClient.PURPOSE_GENERATE, "m1", 200, 800, 9000, true, false, "t1"));
        s.record(call(LlmClient.PURPOSE_SUMMARIZE, "m1", 300, 100, 2000, false, false, "t2"));

        Map<String, Object> totals = s.totals(1);
        assertEquals(3, totals.get("calls"));
        assertEquals(1550L, totals.get("tokens"));
        assertEquals(600L, totals.get("promptTokens"));
        assertEquals(950L, totals.get("completionTokens"));
        assertEquals(12000L, totals.get("totalMs"));
        assertEquals(9000L, totals.get("maxMs"));
        // 失败调用同样计入耗时与调用数，只记成功会让成功率与延迟双双失真
        assertEquals(1, totals.get("failures"));
        assertEquals(4000L, totals.get("avgMs"));
    }

    @Test
    void groupsByPurposeAndModelSortedByTokens() {
        LlmUsageStore s = store(1000);
        s.record(call(LlmClient.PURPOSE_PLAN, "cheap", 100, 50, 500, true, false, ""));
        s.record(call(LlmClient.PURPOSE_GENERATE, "cheap", 100, 3000, 8000, true, false, ""));
        s.record(call(LlmClient.PURPOSE_PLAN, "strong", 200, 100, 900, true, false, ""));

        List<Map<String, Object>> byPurpose = s.byPurpose(1);
        assertEquals(LlmClient.PURPOSE_GENERATE, byPurpose.get(0).get("key"));
        assertEquals(3100L, byPurpose.get(0).get("tokens"));

        List<Map<String, Object>> byModel = s.byModel(1);
        assertEquals(2, byModel.size());
        assertEquals("cheap", byModel.get(0).get("key"));
        assertEquals(3250L, byModel.get(0).get("tokens"));
    }

    @Test
    void taskBreakdownShowsWhichStageCostMost() {
        LlmUsageStore s = store(1000);
        s.record(call(LlmClient.PURPOSE_PLAN, "m", 100, 50, 800, true, false, "task-a"));
        s.record(call(LlmClient.PURPOSE_GENERATE, "m", 200, 400, 7000, true, false, "task-a"));
        s.record(call(LlmClient.PURPOSE_PLAN, "m", 50, 20, 300, true, false, "task-b"));

        List<Map<String, Object>> rows = s.taskBreakdown("task-a");
        assertEquals(2, rows.size());
        // 按耗时倒序：生成阶段是大头
        assertEquals(LlmClient.PURPOSE_GENERATE, rows.get(0).get("purpose"));
        assertEquals(7000L, rows.get(0).get("totalMs"));

        List<Map<String, Object>> top = s.topTasks(1, 10);
        assertEquals("task-a", top.get(0).get("taskId"));
        assertTrue((Long) top.get(0).get("tokens") > (Long) top.get(1).get("tokens"));
        assertEquals(List.of(), s.taskBreakdown(""));
        assertEquals(List.of(), s.taskBreakdown(null));
    }

    @Test
    void hourlySeriesIsZeroFilledSoChartsDoNotLieAboutGaps() {
        LlmUsageStore s = store(1000);
        s.record(call(LlmClient.PURPOSE_CHAT, "m", 10, 10, 100, true, false, ""));

        List<Map<String, Object>> series = s.hourly(4);
        assertEquals(4, series.size());
        // 补零：没有调用的小时也要出现，否则前端画出来的曲线会凭空少掉几个小时
        Map<String, Object> last = series.get(3);
        assertEquals(1, last.get("calls"));
        assertEquals(20L, last.get("tokens"));
        assertEquals(0, series.get(0).get("calls"));
    }

    @Test
    void recentFlowShowsEstimatedFlagAndError() {
        LlmUsageStore s = store(1000);
        s.record(call(LlmClient.PURPOSE_GENERATE, "m", 100, 200, 5000, true, true, "t9"));
        s.record(call(LlmClient.PURPOSE_PLAN, "m", 0, 0, 100, false, true, "t9"));

        List<Map<String, Object>> recent = s.recent(10);
        assertEquals(2, recent.size());
        Map<String, Object> failed = recent.get(0);
        assertFalse((Boolean) failed.get("ok"));
        assertEquals("超时", failed.get("error"));
        Map<String, Object> estimated = recent.get(1);
        assertTrue((Boolean) estimated.get("estimated"));
        assertEquals("t9", estimated.get("taskId"));
    }

    @Test
    void trimsToMaxRowsKeepingNewestAndClears() {
        LlmUsageStore s = store(2);
        s.record(call(LlmClient.PURPOSE_CHAT, "m", 1, 1, 10, true, false, "old"));
        s.record(call(LlmClient.PURPOSE_CHAT, "m", 2, 2, 20, true, false, "mid"));
        s.record(call(LlmClient.PURPOSE_CHAT, "m", 3, 3, 30, true, false, "new"));
        assertEquals(3, s.count());

        s.init();
        assertEquals(2, s.count());
        assertEquals("new", s.recent(1).get(0).get("taskId"));

        assertEquals(2, s.clear());
        assertEquals(0, s.count());
        assertNotNull(s.totals(1));
    }
}
