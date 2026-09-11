package com.agentflow.signoz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 链路分析记录存储：去重写入、检索、统计、删除 */
class TraceAnalysisStoreTest {

    private static TraceAnalysisStore store(Path dir) {
        TraceAnalysisStore s = new TraceAnalysisStore(dir.resolve("t.db").toString(), 2000);
        s.init();
        return s;
    }

    private static TraceAnalysisStore.AnalysisRecord rec(String traceId, boolean found, boolean failed,
                                                         String failurePoint, String errorClass,
                                                         String signature, List<String> services,
                                                         String kbCaseId, String digest) {
        return new TraceAnalysisStore.AnalysisRecord(0, traceId, "", "7d", 8, services, found, failed,
                failurePoint, errorClass, signature, 58.63, "dev", kbCaseId, "STRONG", 1, digest);
    }

    @Test
    void insertsAndListsNewestFirst(@TempDir Path dir) {
        TraceAnalysisStore s = store(dir);
        s.record(rec("aaa0000000000000000000000000001", true, true, "pay-service / POST 余额",
                "dm.jdbc.driver.DMException", "Dao.selectList", List.of("pay-service"), "INC-1", "摘要一"));
        s.record(rec("bbb0000000000000000000000000002", true, false, "gateway / requests",
                "", "gateway.server.requests", List.of("gateway-service"), "", "摘要二"));

        List<TraceAnalysisStore.AnalysisRecord> items = s.list(null, 50, 0);
        assertEquals(2, items.size());
        assertEquals(2, s.count(null));
        TraceAnalysisStore.AnalysisRecord first = items.get(0);
        assertNotNull(first.analyzedAt());
        assertTrue(first.analyzedAt().startsWith("20"), first.analyzedAt());
        assertTrue(items.stream().anyMatch(r -> r.traceId().endsWith("2")));
    }

    /** 同一 trace 重复分析只更新并累加次数，不产生重复行（否则列表会被重复项淹没） */
    @Test
    void sameTraceUpsertsInsteadOfDuplicating(@TempDir Path dir) {
        TraceAnalysisStore s = store(dir);
        long id1 = s.record(rec("ccc0000000000000000000000000003", true, true, "pay-service / POST 余额",
                "dm.jdbc.driver.DMException", "Dao.selectList", List.of("pay-service"), "", "摘要一"));
        long id2 = s.record(rec("ccc0000000000000000000000000003", true, true, "pay-service / POST 余额",
                "dm.jdbc.driver.DMException", "Dao.selectList", List.of("pay-service"), "INC-9", "摘要二"));

        assertEquals(id1, id2, "同一 trace 应复用同一行");
        assertEquals(1, s.count(null));
        TraceAnalysisStore.AnalysisRecord r = s.get(id1);
        assertEquals(2, r.analyzeCount(), "重复分析应累加次数");
        assertEquals("INC-9", r.kbCaseId(), "字段应刷新为最新一次分析");
    }

    @Test
    void keywordSearchMatchesSignatureServiceAndKbCase(@TempDir Path dir) {
        TraceAnalysisStore s = store(dir);
        s.record(rec("ddd0000000000000000000000000004", true, true, "pay-service / POST 余额",
                "dm.jdbc.driver.DMException", "SecurityDepositDetailDao.selectListByQuery",
                List.of("pay-service"), "INC-20260908-001", "摘要"));
        s.record(rec("eee0000000000000000000000000005", true, false, "gateway / requests",
                "", "gateway.server.requests", List.of("gateway-service"), "", "摘要"));

        assertEquals(1, s.count("SecurityDepositDetailDao"));
        assertEquals(1, s.count("pay-service"));
        assertEquals(1, s.count("INC-20260908-001"));
        assertEquals(1, s.count("DMException"));
        assertEquals(0, s.count("不存在的关键字"));
        assertEquals(1, s.list("pay-service", 50, 0).size());
        // 检索只覆盖元数据，不扫摘要正文（保证查询可预期）
        assertEquals(0, s.count("摘要"));
    }

    @Test
    void statsAggregatesFailuresAndTops(@TempDir Path dir) {
        TraceAnalysisStore s = store(dir);
        s.record(rec("f00000000000000000000000000006", true, true, "pay-service / POST 余额",
                "dm.jdbc.driver.DMException", "Dao.selectList", List.of("pay-service", "gateway-service"),
                "INC-1", "d"));
        s.record(rec("f00000000000000000000000000007", true, true, "pay-service / POST 充值",
                "dm.jdbc.driver.DMException", "Dao.selectList", List.of("pay-service"), "", "d"));
        s.record(rec("f00000000000000000000000000008", false, false, "", "", "", List.of(), "", "未找到"));

        TraceAnalysisStore.Stats st = s.stats();
        assertEquals(3, st.total());
        assertEquals(2, st.failed());
        assertEquals(1, st.notFound());
        assertEquals(1, st.withCase());
        assertEquals(24, st.totalSpans(), "rec() 每条都带 8 个 span，3 条共 24");
        // pay-service 出现在 2 条记录里，应排第一
        assertEquals("pay-service", st.topServices().get(0).get("name"));
        assertEquals(2, st.topServices().get(0).get("count"));
        assertEquals("Dao.selectList", st.topSignatures().get(0).get("name"));
    }

    @Test
    void deleteAndClear(@TempDir Path dir) {
        TraceAnalysisStore s = store(dir);
        long id = s.record(rec("a0000000000000000000000000000009", true, true, "p / o",
                "E", "Sig", List.of("svc"), "", "d"));
        s.record(rec("a000000000000000000000000000000a", true, true, "p / o",
                "E", "Sig", List.of("svc"), "", "d"));

        assertTrue(s.delete(id));
        assertFalse(s.delete(id), "重复删除应返回 false");
        assertEquals(1, s.count(null));
        assertEquals(1, s.clear());
        assertEquals(0, s.count(null));
        assertNull(s.get(id));
    }
}
