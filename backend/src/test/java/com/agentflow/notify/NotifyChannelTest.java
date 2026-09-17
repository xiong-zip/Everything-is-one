package com.agentflow.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通道类型识别与 Webhook 地址脱敏（列表接口不能把可用凭据回传给前端） */
class NotifyChannelTest {

    @Test
    void detectTypeByUrl() {
        assertEquals(NotifyChannel.WECOM,
                NotifyChannel.detectType("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc"));
        assertEquals(NotifyChannel.DINGTALK,
                NotifyChannel.detectType("https://oapi.dingtalk.com/robot/send?access_token=abc"));
        // 认不出来时按企微处理（本项目主用企微）
        assertEquals(NotifyChannel.WECOM, NotifyChannel.detectType("https://example.com/hook"));
        assertEquals(NotifyChannel.WECOM, NotifyChannel.detectType(null));
    }

    @Test
    void maskedUrlKeepsOnlyKeyTail() {
        // 用同格式的假 key：真 key 绝不写进仓库
        String masked = new NotifyChannel("群", NotifyChannel.WECOM,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=aaaa0000-1111-2222-3333-444444441073", null)
                .maskedUrl();

        assertTrue(masked.startsWith("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key="));
        assertTrue(masked.endsWith("1073"), "应保留 key 末 4 位便于辨认");
        assertFalse(masked.contains("aaaa0000"), "完整 key 不得回传");
    }

    @Test
    void maskedUrlHandlesShortOrOddInput() {
        assertEquals("", new NotifyChannel("群", NotifyChannel.WECOM, "", null).maskedUrl());
        assertEquals("", new NotifyChannel("群", NotifyChannel.WECOM, null, null).maskedUrl());
        // 没有 key 参数时不该抛异常
        assertFalse(new NotifyChannel("群", NotifyChannel.WECOM, "https://example.com/hook", null)
                .maskedUrl().isEmpty());
    }

    @Test
    void typeNameForUi() {
        assertEquals("企微", new NotifyChannel("a", NotifyChannel.WECOM, "u", null).typeName());
        assertEquals("钉钉", new NotifyChannel("b", NotifyChannel.DINGTALK, "u", null).typeName());
        assertTrue(new NotifyChannel("c", NotifyChannel.DINGTALK, "u", null).dingtalk());
        assertFalse(new NotifyChannel("d", NotifyChannel.WECOM, "u", null).dingtalk());
    }
}
