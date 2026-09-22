package com.agentflow.alarm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排查指令生成：模板占位符替换与三档默认入口选择。
 * 这两块决定了「收到告警后到底让 Agent 干什么」，是值守行为里最需要钉住的部分。
 */
class AlarmServiceCommandTest {

    private static AlarmEvent event(String service, String traceId, String alertName) {
        return new AlarmEvent("webhook", alertName, "critical", "firing", service, traceId, "描述",
                "k", "{}");
    }

    @Test
    void rendersAllPlaceholders() {
        AlarmEvent e = event("pay-service", "c4ea16342cf1a0526d22fa20d57c9e2a", "支付错误率升高");
        assertEquals("链路 c4ea16342cf1a0526d22fa20d57c9e2a 服务 pay-service 告警 支付错误率升高 级别 critical",
                AlarmService.render("链路 {traceId} 服务 {service} 告警 {alertName} 级别 {severity}", e));
    }

    @Test
    void missingFieldsRenderEmptyRatherThanNullLiteral() {
        AlarmEvent e = event("", "", "");
        String out = AlarmService.render("[{traceId}][{service}][{message}]", e);
        assertEquals("[][][描述]", out);
        assertFalse(out.contains("null"));
    }

    @Test
    void titlePlaceholderFallsBackToGenericText() {
        assertEquals("监控告警", AlarmService.render("{title}", event("", "", "")));
        assertEquals("服务 pay-service 告警", AlarmService.render("{title}", event("pay-service", "", "")));
    }

    @Test
    void collapsesWhitespaceLeftByEmptyPlaceholders() {
        // 占位符为空会留下多余空格；指令是要发给规划器的，多空格会让同一模板产生不同指令文本
        assertEquals("排查 pay-service 结束",
                AlarmService.render("排查   {service}\n\n结束", event("pay-service", "", "")));
    }

    @Test
    void defaultCommandPrefersTraceIdThenServiceThenGeneric() {
        String withTrace = AlarmService.defaultCommand(true, true);
        assertTrue(withTrace.contains("{traceId}"), withTrace);
        assertTrue(withTrace.contains("gitlab.changes"), withTrace);

        String withService = AlarmService.defaultCommand(false, true);
        assertTrue(withService.contains("{service}"), withService);
        assertTrue(withService.contains("Pod"), withService);
        assertFalse(withService.contains("{traceId}"), withService);

        String generic = AlarmService.defaultCommand(false, false);
        assertTrue(generic.contains("{title}"), generic);
        assertTrue(generic.contains("{message}"), generic);
    }

    @Test
    void defaultCommandRendersIntoUsableInstruction() {
        // 三档模板都必须能被 render 完整吃下，不留未替换的占位符
        for (boolean hasTrace : new boolean[]{true, false}) {
            for (boolean hasService : new boolean[]{true, false}) {
                String rendered = AlarmService.render(AlarmService.defaultCommand(hasTrace, hasService),
                        event("pay-service", "c4ea16342cf1a0526d22fa20d57c9e2a", "支付错误率升高"));
                assertFalse(rendered.contains("{"), rendered);
                assertFalse(rendered.contains("}"), rendered);
            }
        }
    }
}
