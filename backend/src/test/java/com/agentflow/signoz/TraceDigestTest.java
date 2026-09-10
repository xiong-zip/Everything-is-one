package com.agentflow.signoz;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 链路解析 / 指纹推导 / 摘要渲染：全部基于纯逻辑，不触网 */
class TraceDigestTest {

    /** 真实形状的载荷：gateway → outp-settle → feign → pay-service（达梦 JdbcType 报错） */
    private static final String PAYLOAD = """
            {"status":"success","data":{"type":"raw","meta":{"rowsScanned":28047},
            "warning":null,
            "data":{"results":[{"queryName":"A","nextCursor":"","rows":[
            {"data":{"service.name":"gateway-service","name":"gateway.server.requests","spanID":"a1","parentSpanID":"","durationNano":58629451,"hasError":false,"statusCodeString":"Unset","statusMessage":"","spanKind":"Server","timestamp":"2026-09-08T01:48:45.872794113Z","http.response.status_code":"","deployment.environment":"dev"}},
            {"data":{"service.name":"gateway-service","name":"[ROUTE] lb://outp-settle-service","spanID":"a2","parentSpanID":"a1","durationNano":52600000,"hasError":false,"statusCodeString":"Unset","statusMessage":"","spanKind":"Client","timestamp":"2026-09-08T01:48:45.874000000Z","http.response.status_code":"","deployment.environment":"dev"}},
            {"data":{"service.name":"outp-settle-service","name":"[HTTP] POST 获取患者账户信息","spanID":"a3","parentSpanID":"a2","durationNano":52249392,"hasError":true,"statusCodeString":"Error","statusMessage":"### Error querying database.  Cause: org.apache.ibatis.type.TypeException: Error setting non null for parameter #1 with JdbcType null . Cause: dm.jdbc.driver.DMException: Not support this type\\n### The error may involve com.zoe.pay.business.dao.SecurityDepositDetailDao.selectListByQuery","spanKind":"Server","timestamp":"2026-09-08T01:48:45.876000000Z","http.response.status_code":"200","deployment.environment":"dev"}},
            {"data":{"service.name":"outp-settle-service","name":"[Feign] 调用 [支付域] getAccountBalance","spanID":"a4","parentSpanID":"a3","durationNano":39990000,"hasError":true,"statusCodeString":"Error","statusMessage":"### Error querying database.  Cause: org.apache.ibatis.type.TypeException: Error setting non null for parameter #1 with JdbcType null . Cause: dm.jdbc.driver.DMException: Not support this type\\n### The error may involve com.zoe.pay.business.dao.SecurityDepositDetailDao.selectListByQuery","spanKind":"Client","timestamp":"2026-09-08T01:48:45.878000000Z","http.response.status_code":"200","deployment.environment":"dev"}},
            {"data":{"service.name":"pay-service","name":"[HTTP] POST 获取账户余额","spanID":"a5","parentSpanID":"a4","durationNano":35193914,"hasError":true,"statusCodeString":"Error","statusMessage":"### Error querying database.  Cause: org.apache.ibatis.type.TypeException: Error setting non null for parameter #1 with JdbcType null . Cause: dm.jdbc.driver.DMException: Not support this type\\n### The error may exist in com/zoe/pay/business/dao/SecurityDepositDetailDao.java (best guess)\\n### The error may involve com.zoe.pay.business.dao.SecurityDepositDetailDao.selectListByQuery\\n### SQL: SELECT * FROM zoe_pay.security_deposit_detail_record INNER JOIN zoe_pay.security_deposit_account_record ON zoe_pay.security_deposit_detail_record.security_deposit_account_id = zoe_pay.security_deposit_account_record.security_deposit_account_id WHERE zoe_pay.security_deposit_detail_record.security_deposit_item_id = ?","spanKind":"Server","timestamp":"2026-09-08T01:48:45.880000000Z","http.response.status_code":"200","deployment.environment":"dev"}},
            {"data":{"service.name":"pay-service","name":"[SQL] SELECT","spanID":"a6","parentSpanID":"a5","durationNano":2750000,"hasError":false,"statusCodeString":"Unset","statusMessage":"","spanKind":"Internal","timestamp":"2026-09-08T01:48:45.900000000Z","http.response.status_code":"","deployment.environment":"dev"}},
            {"data":{"service.name":"pay-service","name":"[SQL] SELECT","spanID":"a7","parentSpanID":"a5","durationNano":4050000,"hasError":false,"statusCodeString":"Unset","statusMessage":"","spanKind":"Internal","timestamp":"2026-09-08T01:48:45.905000000Z","http.response.status_code":"","deployment.environment":"dev"}},
            {"data":{"service.name":"pay-service","name":"[SQL] SELECT","spanID":"a8","parentSpanID":"a5","durationNano":2600000,"hasError":false,"statusCodeString":"Unset","statusMessage":"","spanKind":"Internal","timestamp":"2026-09-08T01:48:45.907000000Z","http.response.status_code":"","deployment.environment":"dev"}}
            ]}]}}}
            """;

    @Test
    void parsesSpans() {
        TraceDigest.ParsedSpans parsed = TraceDigest.parseSpans(PAYLOAD);
        assertNull(parsed.notice());
        assertEquals(8, parsed.spans().size());
        assertEquals("gateway-service", parsed.spans().get(0).service());
    }

    @Test
    void findsRootAndErrorOrigin() {
        List<TraceSpan> spans = TraceDigest.parseSpans(PAYLOAD).spans();
        TraceSpan root = TraceDigest.mainRoot(spans);
        assertNotNull(root);
        assertEquals("gateway.server.requests", root.name());

        TraceSpan origin = TraceDigest.errorOrigin(spans);
        assertNotNull(origin);
        // 三个 span 都报错，但最深的是 pay-service —— 失败源头而非转发层
        assertEquals("pay-service", origin.service());
        assertEquals("[HTTP] POST 获取账户余额", origin.name());
    }

    @Test
    void derivesFingerprintFromDeepestException() {
        List<TraceSpan> spans = TraceDigest.parseSpans(PAYLOAD).spans();
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);
        // 异常链最后一个异常类即最深根因
        assertEquals("dm.jdbc.driver.DMException", fp.errorClass());
        // MyBatis 报错里的 may-involve 方法是最有辨识度的特征
        assertEquals("SecurityDepositDetailDao.selectListByQuery", fp.signature());
        assertEquals("pay-service / [HTTP] POST 获取账户余额", fp.span());
        assertTrue(fp.status().contains("HTTP 200 + OTel Error"), fp.status());
        assertTrue(fp.status().contains("根 span 无错误"), fp.status());
        assertTrue(fp.keywords().contains("DMException"), fp.keywords().toString());
    }

    @Test
    void renderPutsConclusionFirst() {
        List<TraceSpan> spans = TraceDigest.parseSpans(PAYLOAD).spans();
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);
        String text = TraceDigest.render("c4ea16342cf1a0526d22fa20d57c9e2a", "7d", spans, List.of(),
                "知识库命中[强]：INC-20260908-001", null);
        String[] lines = text.split("\n");
        assertTrue(lines[0].contains("c4ea16342cf1a0526d22fa20d57c9e2a"), lines[0]);
        assertTrue(lines[0].contains("58.63"), lines[0]);
        // 判定与知识库命中必须在第一屏
        assertTrue(lines[1].startsWith("判定：失败链路"), lines[1]);
        assertTrue(lines[2].contains("INC-20260908-001"), lines[2]);
        // 失败传播链要能看出来自 pay-service
        assertTrue(text.contains("失败传播链"), text);
        assertTrue(text.contains("pay-service / [HTTP] POST 获取账户余额"), text);
        assertTrue(text.contains("Not support this type"), "异常原文必须出现");
        assertFalse(fp.signature().isEmpty());
    }

    @Test
    void handlesEmptyResult() {
        String empty = "{\"status\":\"success\",\"data\":{\"data\":{\"results\":[{\"queryName\":\"A\",\"rows\":null}]}}}";
        TraceDigest.ParsedSpans parsed = TraceDigest.parseSpans(empty);
        assertTrue(parsed.spans().isEmpty());
        assertNull(TraceDigest.errorOrigin(parsed.spans()));
        assertNull(TraceDigest.mainRoot(parsed.spans()));
    }

    @Test
    void surfacesServerWarningWhenOutOfRange() {
        String outOfRange = """
                {"status":"success","data":{"warning":{"message":"Encountered warnings","warnings":[
                {"message":"Query A references a trace_id that exists between 2026-09-08T01:48:44Z and 2026-09-08T01:48:46Z (UTC) but lies outside the selected time range"}]},
                "data":{"results":[{"queryName":"A","rows":null}]}}}
                """;
        TraceDigest.ParsedSpans parsed = TraceDigest.parseSpans(outOfRange);
        assertTrue(parsed.spans().isEmpty());
        assertNotNull(parsed.notice());
        assertTrue(parsed.notice().contains("outside the selected time range"), parsed.notice());
    }

    @Test
    void parsesLogsAndDedupesNothingPrematurely() {
        String logs = """
                {"status":"success","data":{"data":{"results":[{"queryName":"A","rows":[
                {"data":{"severity_text":"ERROR","service.name":"pay-service","body":"boom","timestamp":"2026-09-08T01:48:45Z"}},
                {"data":{"severity_text":"WARN","service.name":"pay-service","body":"","timestamp":"2026-09-08T01:48:45Z"}}
                ]}]}}}
                """;
        List<TraceDigest.LogLine> lines = TraceDigest.parseLogs(logs);
        assertEquals(1, lines.size());
        assertEquals("ERROR", lines.get(0).severity());
        assertEquals("boom", lines.get(0).body());
    }

    @Test
    void successTraceFingerprintHasNoException() {
        List<TraceSpan> spans = List.of(
                new TraceSpan("api", "[HTTP] GET /ok", "s1", "", 5_000_000L, false, "Unset", "",
                        "Server", "2026-09-08T01:00:00Z", "200", "dev"));
        TraceDigest.Fingerprint fp = TraceDigest.fingerprint(spans);
        assertTrue(fp.errorClass().contains("无异常"), fp.errorClass());
        assertNull(TraceDigest.errorOrigin(spans));
    }

    @Test
    void traceIdResolvedFromFreeText() {
        Map<String, Object> empty = new HashMap<>();
        assertEquals("c4ea16342cf1a0526d22fa20d57c9e2a",
                SigNozTraceTool.resolveTraceId(empty, "链路分析c4ea16342cf1a0526d22fa20d57c9e2a"));
        assertEquals("c4ea16342cf1a0526d22fa20d57c9e2a",
                SigNozTraceTool.resolveTraceId(Map.of("traceId", "C4EA16342CF1A0526D22FA20D57C9E2A"), null));
        assertNull(SigNozTraceTool.resolveTraceId(empty, "帮我看看数据库"));
    }

    /**
     * 关键约束：引擎只把工具结果的前 1600 字符喂给写作模型（AgentEngine 里的 truncate(detail, 1600)），
     * 所以结论、知识库命中、失败链、异常原文必须全部落在前 1600 字符内，否则报告会丢根因。
     */
    @Test
    void essentialsFitInEngineTruncationWindow() {
        List<TraceSpan> spans = TraceDigest.parseSpans(PAYLOAD).spans();
        String kb = "知识库命中[强]：INC-20260908-001 · 达梦驱动不支持参数类型导致押金明细查询失败"
                + "（历史第 1 次，最近 2026-09-08T09:48:45+08:00）\n历史处置：显式指定 JdbcType；修正异常状态映射";
        String text = TraceDigest.render("c4ea16342cf1a0526d22fa20d57c9e2a", "7d", spans, List.of(), kb, null);
        String head = text.length() <= 1600 ? text : text.substring(0, 1600);

        assertTrue(head.contains("判定：失败链路"), "判定必须在截断窗口内");
        assertTrue(head.contains("INC-20260908-001"), "知识库命中必须在截断窗口内");
        assertTrue(head.contains("失败传播链"), "失败链必须在截断窗口内");
        assertTrue(head.contains("pay-service / [HTTP] POST 获取账户余额"), "失败源头必须在截断窗口内");
        assertTrue(head.contains("Not support this type"), "异常原文必须在截断窗口内");
    }
}
