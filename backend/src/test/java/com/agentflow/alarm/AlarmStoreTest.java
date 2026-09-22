package com.agentflow.alarm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 告警值守记录：去重窗口、结果回填、上限裁剪与汇总 */
class AlarmStoreTest {

    @TempDir
    Path tmp;

    private static AlarmStore store(Path dir, int maxRows) {
        AlarmStore s = new AlarmStore(dir.resolve("alarm.db").toString(), maxRows);
        s.init();
        return s;
    }

    private static AlarmEvent event(String alertName, String service, String traceId) {
        return new AlarmEvent("webhook", alertName, "critical", "firing", service, traceId,
                "描述", alertName + "|" + service + "|" + traceId + "|firing", "{}");
    }

    @Test
    void dedupWindowCountsOnlySameKeyInsideWindow() {
        AlarmStore s = store(tmp, 100);
        AlarmEvent e = event("支付错误率升高", "pay-service", "");
        assertEquals(0, s.recentCount(e.dedupKey(), 600));

        s.insert(e, "running");
        assertEquals(1, s.recentCount(e.dedupKey(), 600));

        // 换了服务就是另一条告警，不该被去重掉
        assertEquals(0, s.recentCount(event("支付错误率升高", "order-service", "").dedupKey(), 600));
        // 窗口为 0 表示不去重
        assertEquals(0, s.recentCount(e.dedupKey(), 0));
    }

    @Test
    void finishBackfillsConclusionAndPushFlag() {
        AlarmStore s = store(tmp, 100);
        long id = s.insert(event("A", "svc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), "running");
        assertTrue(id > 0);

        AlarmStore.Record running = s.list(1, 0).get(0);
        assertEquals("running", running.state());

        s.finish(id, "done", "task-123", "根因是连接池耗尽", "完整结论", true, 12345L);
        AlarmStore.Record done = s.list(1, 0).get(0);
        assertEquals("done", done.state());
        assertEquals("task-123", done.runTaskId());
        assertEquals("根因是连接池耗尽", done.summary());
        assertEquals("完整结论", done.output());
        assertTrue(done.pushed());
        assertEquals(12345L, done.elapsedMs());
    }

    @Test
    void finishOnMissingRecordIsNoOpInsteadOfThrowing() {
        AlarmStore s = store(tmp, 100);
        // 落库失败时 insert 返回 -1，回填必须安静跳过，否则值守线程会因收尾动作再炸一次
        s.finish(-1, "error", "", "x", "", false, 1);
        assertEquals(0, s.count());
    }

    @Test
    void trimsToMaxRowsOnInitKeepingNewest() {
        Path dir = tmp;
        AlarmStore s = store(dir, 2);
        s.insert(event("A", "s1", ""), "done");
        s.insert(event("B", "s2", ""), "done");
        s.insert(event("C", "s3", ""), "done");
        assertEquals(3, s.count());

        // 重新 init 相当于下次启动，裁剪到上限并保留最新的
        s.init();
        assertEquals(2, s.count());
        List<AlarmStore.Record> items = s.list(10, 0);
        assertEquals("C", items.get(0).alertName());
        assertEquals("B", items.get(1).alertName());
    }

    @Test
    void statsAndDeleteAndClear() {
        AlarmStore s = store(tmp, 100);
        long id = s.insert(event("A", "pay-service", ""), "done");
        s.insert(event("B", "pay-service", ""), "skipped");
        s.finish(id, "done", "t", "摘要", "", true, 100);

        Map<String, Object> stats = s.stats();
        assertEquals(2, stats.get("total"));
        assertEquals(1, stats.get("pushed"));
        assertNotNull(stats.get("byState"));
        assertFalse(((List<?>) stats.get("topServices")).isEmpty());

        assertTrue(s.delete(id));
        assertFalse(s.delete(id));
        assertEquals(1, s.clear());
        assertEquals(0, s.count());
    }
}
