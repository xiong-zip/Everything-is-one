package com.agentflow.signoz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 健康巡检的纯逻辑：指标解析、风险分级与窗口换算 */
class HealthScanToolTest {

    private static final String PAYLOAD = """
            {"data":[
              {"serviceName":"pay-service","numCalls":416510,"numErrors":325,
               "errorRate":0.07802933903147584,"fourXXRate":0,"num4XX":0,
               "avgDuration":7901276.5,"p99":24092907.0},
              {"serviceName":"tmplrepo-service","numCalls":268282,"numErrors":0,
               "errorRate":0,"fourXXRate":0,"num4XX":0,
               "avgDuration":2237128.9,"p99":8565195.2},
              {"serviceName":"slow-service","numCalls":1200,"numErrors":0,
               "errorRate":0,"fourXXRate":0,"num4XX":0,
               "avgDuration":507541.3,"p99":4200000000.0}
            ],"pagination":{"total":3,"hasMore":false}}
            """;

    /* ---------- parseServices ---------- */

    @Test
    void parsesServiceStatsWithUnitConversion() {
        List<HealthScanTool.SvcStat> stats = HealthScanTool.parseServices(PAYLOAD);
        assertEquals(3, stats.size());

        HealthScanTool.SvcStat pay = stats.get(0);
        assertEquals("pay-service", pay.name());
        assertEquals(416510, pay.calls());
        assertEquals(325, pay.errors());
        // errorRate 服务端返回的已是百分数（325/416510=0.078% 就是 0.078），不得再 ×100；
        // p99 是纳秒 → 毫秒
        assertEquals(0.078, pay.errorPct(), 0.001);
        assertEquals(24.09, pay.p99Ms(), 0.01);

        HealthScanTool.SvcStat slow = stats.get(2);
        assertEquals(4200.0, slow.p99Ms(), 0.01);
    }

    @Test
    void blankOrBrokenPayloadGivesEmptyList() {
        assertTrue(HealthScanTool.parseServices(null).isEmpty());
        assertTrue(HealthScanTool.parseServices("  ").isEmpty());
        assertTrue(HealthScanTool.parseServices("not-json{{{").isEmpty());
    }

    /* ---------- classify ---------- */

    @Test
    void highRiskWhenErrorRateOverThreshold() {
        HealthScanTool.SvcStat s = new HealthScanTool.SvcStat("a", 1000, 80, 8.0, 100, 50, 0, 0);
        assertEquals(2, HealthScanTool.classify(s, 5.0, 3000, 50));
    }

    @Test
    void mediumRiskWhenP99OverThreshold() {
        HealthScanTool.SvcStat s = new HealthScanTool.SvcStat("a", 1000, 0, 0, 5000, 100, 0, 0);
        assertEquals(1, HealthScanTool.classify(s, 5.0, 3000, 50));
    }

    @Test
    void mediumRiskWhenErrorRateHalfThresholdWithDoubleVolume() {
        HealthScanTool.SvcStat s = new HealthScanTool.SvcStat("a", 200, 6, 3.0, 100, 50, 0, 0);
        assertEquals(1, HealthScanTool.classify(s, 5.0, 3000, 50));
    }

    @Test
    void lowVolumeServiceNeverFlagged() {
        // 3 个请求错 1 个 = 33%：小样本不是风险
        HealthScanTool.SvcStat s = new HealthScanTool.SvcStat("a", 3, 1, 33.3, 99999, 50, 0, 0);
        assertEquals(0, HealthScanTool.classify(s, 5.0, 3000, 50));
    }

    @Test
    void healthyServiceIsClean() {
        HealthScanTool.SvcStat s = new HealthScanTool.SvcStat("a", 10000, 1, 0.01, 200, 50, 0, 0);
        assertEquals(0, HealthScanTool.classify(s, 5.0, 3000, 50));
    }

    /* ---------- rangeHours ---------- */

    @Test
    void rangeHoursSupportsMhD() {
        assertEquals(0.5, HealthScanTool.rangeHours("30m"), 0.001);
        assertEquals(6, HealthScanTool.rangeHours("6h"), 0.001);
        assertEquals(168, HealthScanTool.rangeHours("7d"), 0.001);
        assertEquals(6, HealthScanTool.rangeHours("junk"), 0.001);
        assertEquals(6, HealthScanTool.rangeHours(null), 0.001);
    }

    @Test
    void riskLevelText() {
        assertEquals("高", HealthScanTool.Risk.levelText(2));
        assertEquals("中", HealthScanTool.Risk.levelText(1));
    }
}
