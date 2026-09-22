package com.agentflow.alarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警载荷解析：跨平台字段归一化、trace ID 兜底提取、状态识别。
 * 这些用例的作用是「换监控平台时不必改代码」这个承诺的可验证依据。
 */
class AlarmParserTest {

    @Test
    void parsesAlertmanagerPayloadPickingFiringAlert() {
        String body = """
                {
                  "receiver": "signoz-alerts",
                  "status": "firing",
                  "commonLabels": {"severity": "warning"},
                  "alerts": [
                    {
                      "status": {"state": "resolved"},
                      "labels": {"alertname": "已恢复的告警", "service_name": "old-service"},
                      "annotations": {"summary": "这条已经恢复了"}
                    },
                    {
                      "status": {"state": "firing"},
                      "labels": {"alertname": "支付服务错误率升高", "service_name": "pay-service"},
                      "annotations": {
                        "summary": "pay-service 5xx 比例超过 5%",
                        "description": "链路参考 c4ea16342cf1a0526d22fa20d57c9e2a"
                      }
                    }
                  ]
                }
                """;
        AlarmEvent e = AlarmParser.parse(body);
        assertNotNull(e);
        // 该挑 firing 那条，而不是数组里排前面的 resolved
        assertEquals("支付服务错误率升高", e.alertName());
        assertEquals("pay-service", e.service());
        assertEquals("firing", e.status());
        assertEquals("warning", e.severity());
        assertEquals("c4ea16342cf1a0526d22fa20d57c9e2a", e.traceId());
        assertFalse(e.resolved());
    }

    @Test
    void parsesSigNozStylePayloadWithDottedServiceKey() {
        String body = """
                {
                  "alertName": "网关延迟告警",
                  "severity": "critical",
                  "status": "firing",
                  "labels": {"service.name": "gateway-service", "env": "prod"},
                  "annotations": {"summary": "P99 > 2s"}
                }
                """;
        AlarmEvent e = AlarmParser.parse(body);
        assertNotNull(e);
        assertEquals("网关延迟告警", e.alertName());
        assertEquals("gateway-service", e.service());
        assertEquals("critical", e.severity());
        assertEquals("firing", e.status());
        assertFalse(e.hasTraceId());
        assertTrue(e.hasService());
    }

    @Test
    void parsesPlainTextPayloadByTraceIdRegex() {
        AlarmEvent e = AlarmParser.parse(
                "【告警】订单服务异常\n链路 3f9a1b2c4d5e6f708192a3b4c5d6e7f8 出现大量超时");
        assertNotNull(e);
        assertEquals("3f9a1b2c4d5e6f708192a3b4c5d6e7f8", e.traceId());
        assertEquals("firing", e.status());
        assertTrue(e.message().contains("订单服务异常"));
        assertTrue(e.raw().contains("3f9a1b2c4d5e6f708192a3b4c5d6e7f8"));
    }

    @Test
    void onlyResolvedAlertsStillParsesAsResolved() {
        String body = """
                {"alerts": [{"status": {"state": "resolved"},
                             "labels": {"alertname": "已恢复", "service": "pay-service"}}]}
                """;
        AlarmEvent e = AlarmParser.parse(body);
        assertNotNull(e);
        assertTrue(e.resolved());
    }

    @Test
    void ignoresUnknownStatusValueInsteadOfMisreadingIt() {
        // HTTP 响应那种 status:200 不能被当成告警状态；识别不出就落回默认 firing
        AlarmEvent e = AlarmParser.parse("{\"alertName\": \"X\", \"status\": \"200\"}");
        assertNotNull(e);
        assertEquals("firing", e.status());
        assertEquals("info", e.severityOrInfo());
    }

    @Test
    void keyNameNormalizationTreatsVariantsAsSameField() {
        assertEquals("pay-service", AlarmParser.parse("{\"serviceName\": \"pay-service\"}").service());
        assertEquals("pay-service", AlarmParser.parse("{\"service\": \"pay-service\"}").service());
        assertEquals("pay-service", AlarmParser.parse("{\"service_name\": \"pay-service\"}").service());
        assertEquals("pay-service", AlarmParser.parse("{\"labels\": {\"service.name\": \"pay-service\"}}").service());
        assertEquals("pay-service", AlarmParser.parse("{\"labels\": {\"app\": \"pay-service\"}}").service());
        // 带平台前缀的写法同样认（K8s 场景的标签习惯）
        assertEquals("pay-service", AlarmParser.parse("{\"labels\": {\"k8s.service\": \"pay-service\"}}").service());
    }

    @Test
    void dedupKeyIgnoresUnrelatedFieldsButSeparatesDistinctAlerts() {
        AlarmEvent a = AlarmParser.parse("{\"alertName\":\"A\",\"service\":\"s1\",\"status\":\"firing\"}");
        AlarmEvent b = AlarmParser.parse("{\"alertName\":\"A\",\"service\":\"s1\",\"status\":\"firing\","
                + "\"description\":\"换了一段描述\"}");
        AlarmEvent c = AlarmParser.parse("{\"alertName\":\"A\",\"service\":\"s2\",\"status\":\"firing\"}");
        assertEquals(a.dedupKey(), b.dedupKey());
        assertFalse(a.dedupKey().equals(c.dedupKey()));
    }

    @Test
    void rejectsBlankPayloadAndHandlesBrokenJsonAsText() {
        assertNull(AlarmParser.parse(null));
        assertNull(AlarmParser.parse("   "));
        // 声称 JSON 但语法坏了：降级成纯文本而不是丢掉整条告警
        AlarmEvent broken = AlarmParser.parse("{不是合法 JSON，但含链路 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertNotNull(broken);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", broken.traceId());
    }

    @Test
    void titleFallsBackThroughAlertNameServiceThenGeneric() {
        assertEquals("A", AlarmParser.parse("{\"alertName\":\"A\"}").title());
        assertEquals("服务 pay-service 告警", AlarmParser.parse("{\"service\":\"pay-service\"}").title());
        assertEquals("监控告警", AlarmParser.parse("{\"foo\":\"bar\"}").title());
    }
}
