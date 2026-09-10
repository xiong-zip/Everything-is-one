package com.agentflow.signoz;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 抓取降级：24h 查不到要自动扩到 7d，失败链路要补查日志 */
class TraceFetcherTest {

    /** 24h 窗口的空结果 + "trace 其实存在于别的时间段"提示 */
    private static final String OUT_OF_RANGE = """
            {"status":"success","data":{"warning":{"warnings":[{"message":"Query A references a trace_id that exists between 2026-09-08T01:48:44Z and 2026-09-08T01:48:46Z (UTC) but lies outside the selected time range"}]},
            "data":{"results":[{"queryName":"A","rows":null}]}}}
            """;

    private static final String FOUND = """
            {"status":"success","data":{"data":{"results":[{"queryName":"A","rows":[
            {"data":{"service.name":"gateway-service","name":"gateway.server.requests","spanID":"a1","parentSpanID":"","durationNano":58629451,"hasError":false,"statusCodeString":"Unset","statusMessage":"","spanKind":"Server","timestamp":"2026-09-08T01:48:45.872794113Z","http.response.status_code":"","deployment.environment":"dev"}},
            {"data":{"service.name":"pay-service","name":"[HTTP] POST 获取账户余额","spanID":"a5","parentSpanID":"a1","durationNano":35193914,"hasError":true,"statusCodeString":"Error","statusMessage":"Cause: dm.jdbc.driver.DMException: Not support this type","spanKind":"Server","timestamp":"2026-09-08T01:48:45.880000000Z","http.response.status_code":"200","deployment.environment":"dev"}}
            ]}]}}}
            """;

    private static final String LOGS = """
            {"status":"success","data":{"data":{"results":[{"queryName":"A","rows":[
            {"data":{"severity_text":"ERROR","service.name":"pay-service","body":"dm.jdbc.driver.DMException: Not support this type","timestamp":"2026-09-08T01:48:45Z"}}
            ]}]}}}
            """;

    /** 按时间窗精确指定返回：未列出的窗口视为"不该被查到"，直接报错暴露逻辑偏差 */
    private static SigNozMcpClient clientReturning(Map<String, String> byRange) {
        SigNozMcpClient client = mock(SigNozMcpClient.class);
        when(client.callTool(eq("signoz_get_trace_details"), anyMap())).thenAnswer(inv -> {
            Map<String, Object> args = inv.getArgument(1);
            String range = String.valueOf(args.get("timeRange"));
            String payload = byRange.get(range);
            if (payload == null) {
                throw new IllegalStateException("未预期的查询窗口：" + range);
            }
            return payload;
        });
        when(client.callTool(eq("signoz_search_logs"), anyMap())).thenReturn(LOGS);
        return client;
    }

    private static TraceFetcher fetcher(SigNozMcpClient client) {
        return new TraceFetcher(client, "24h", "7d", 10);
    }

    @Test
    void fallsBackTo7dWhenDefaultWindowMisses() {
        SigNozMcpClient client = clientReturning(Map.of("24h", OUT_OF_RANGE, "7d", FOUND));
        TraceFetcher.Fetched f = fetcher(client).fetch("c4ea16342cf1a0526d22fa20d57c9e2a", null);

        assertTrue(f.found(), "应通过 7d 窗口命中");
        assertEquals("7d", f.rangeUsed());
        assertEquals(2, f.spans().size());
        assertTrue(f.failed());
    }

    @Test
    void failureTraceAlsoFetchesErrorLogs() {
        SigNozMcpClient client = clientReturning(Map.of("24h", OUT_OF_RANGE, "7d", FOUND));
        TraceFetcher.Fetched f = fetcher(client).fetch("c4ea16342cf1a0526d22fa20d57c9e2a", null);

        assertEquals(1, f.logs().size());
        verify(client, times(1)).callTool(eq("signoz_search_logs"), anyMap());
    }

    @Test
    void successTraceSkipsLogQuery() {
        String okOnly = FOUND.replace("\"hasError\":true", "\"hasError\":false")
                .replace("\"statusCodeString\":\"Error\"", "\"statusCodeString\":\"Unset\"");
        SigNozMcpClient client = clientReturning(Map.of("24h", okOnly));

        TraceFetcher.Fetched f = fetcher(client).fetch("c4ea16342cf1a0526d22fa20d57c9e2a", null);
        assertTrue(f.found());
        assertFalse(f.failed());
        assertEquals(0, f.logs().size());
        verify(client, times(0)).callTool(eq("signoz_search_logs"), anyMap());
    }

    @Test
    void keepsServerHintWhenBothWindowsMiss() {
        SigNozMcpClient client = clientReturning(Map.of("24h", OUT_OF_RANGE, "7d", OUT_OF_RANGE));
        TraceFetcher.Fetched f = fetcher(client).fetch("c4ea16342cf1a0526d22fa20d57c9e2a", null);

        assertFalse(f.found());
        assertEquals("7d", f.rangeUsed());
        assertTrue(f.notice().contains("outside the selected time range"),
                "服务端提示必须透出，否则用户无法判断到底该扩到多大窗口：" + f.notice());
    }

    @Test
    void explicitRangeIsRespectedAndStillFallsBack() {
        SigNozMcpClient client = clientReturning(Map.of("1h", OUT_OF_RANGE, "7d", FOUND));
        TraceFetcher.Fetched f = fetcher(client).fetch("c4ea16342cf1a0526d22fa20d57c9e2a", "1h");

        assertTrue(f.found());
        assertEquals("7d", f.rangeUsed());
        verify(client, times(1)).callTool(eq("signoz_get_trace_details"),
                org.mockito.ArgumentMatchers.argThat(a -> "1h".equals(((Map<?, ?>) a).get("timeRange"))));
    }
}
