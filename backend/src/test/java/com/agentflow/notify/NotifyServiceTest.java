package com.agentflow.notify;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推送的两个易错点：企微按 UTF-8 字节限长（中文 3 字节/字），
 * 以及成败判定藏在响应体 errcode 里（HTTP 恒为 200）。
 */
class NotifyServiceTest {

    /* ---------- truncateBytes（按字节截断） ---------- */

    @Test
    void shortContentUntouched() {
        assertEquals("日报已生成", NotifyService.truncateBytes("日报已生成", 1900));
        assertEquals("", NotifyService.truncateBytes(null, 1900));
        assertEquals("", NotifyService.truncateBytes("", 1900));
    }

    @Test
    void chineseTruncatedByBytesNotChars() {
        // 1000 个中文字符 = 3000 字节，超过 1900 字节上限
        String long_ = "根因是空指针".repeat(200);
        String out = NotifyService.truncateBytes(long_, 1900);

        assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 1900, "不得超过字节上限");
        assertTrue(out.contains("已截断"), "应带截断提示");
        // 截断后要基本用满预算（否则等于白白丢内容）
        assertTrue(out.getBytes(StandardCharsets.UTF_8).length >= 1897, "应尽量用满上限");
    }

    @Test
    void truncationNeverSplitsMultiByteChar() {
        // 逐字追加实现不允许产生半个字符（U+FFFD 是解码失败的标志）
        String out = NotifyService.truncateBytes("故障".repeat(500), 1900);
        assertFalse(out.contains("\uFFFD"), "不得截出半个字符");
        assertEquals(out, new String(out.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void asciiTruncatedExactly() {
        String out = NotifyService.truncateBytes("a".repeat(3000), 1900);
        assertEquals(1900, out.getBytes(StandardCharsets.UTF_8).length);
    }

    /* ---------- accepted（errcode 判定） ---------- */

    @Test
    void errcodeZeroIsAccepted() {
        assertTrue(NotifyService.accepted("{\"errcode\":0,\"errmsg\":\"ok\"}"));
    }

    @Test
    void nonZeroErrcodeIsRejected() {
        // 45009 = 接口调用超过限制；93000 = webhook key 无效
        assertFalse(NotifyService.accepted("{\"errcode\":45009,\"errmsg\":\"api freq out of limit\"}"));
        assertFalse(NotifyService.accepted("{\"errcode\":93000,\"errmsg\":\"invalid webhook url\"}"));
    }

    @Test
    void nonJsonResponseTreatedAsSuccess() {
        // 自定义 Webhook 可能返回任意文本，HTTP 层错误已由 RestClient 抛出
        assertTrue(NotifyService.accepted("ok"));
        assertTrue(NotifyService.accepted(""));
        assertTrue(NotifyService.accepted(null));
    }

    /* ---------- parseMention（@ 名单） ---------- */

    @Test
    void parsesMentionList() {
        assertEquals(List.of("13800000000", "13900000000"),
                NotifyService.parseMention("13800000000,13900000000"));
        assertEquals(List.of("13800000000", "13900000000"),
                NotifyService.parseMention(" 13800000000 ; 13900000000 "));
        assertEquals(List.of("@all"), NotifyService.parseMention("@all"));
        assertEquals(List.of(), NotifyService.parseMention(""));
        assertEquals(List.of(), NotifyService.parseMention(null));
        assertEquals(List.of(), NotifyService.parseMention(" , ; "));
    }

    /* ---------- payload（企微/钉钉消息体结构） ---------- */

    private static NotifyChannel wecom() {
        return new NotifyChannel("研发群", NotifyChannel.WECOM,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123", null);
    }

    private static NotifyChannel dingtalk() {
        return new NotifyChannel("告警群", NotifyChannel.DINGTALK,
                "https://oapi.dingtalk.com/robot/send?access_token=abc123", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> textNode(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("text");
    }

    private static Map<String, Object> text(NotifyChannel ch, String title, String content, List<String> mentions) {
        return NotifyService.textPayload(ch, title, content, mentions, 1900);
    }

    @Test
    void payloadShapeWithoutMention() {
        Map<String, Object> payload = text(wecom(), "【AgentFlow 告警】", "pay-service 空指针", List.of());

        assertEquals("text", payload.get("msgtype"));
        assertEquals("【AgentFlow 告警】\n\npay-service 空指针", textNode(payload).get("content"));
        assertFalse(textNode(payload).containsKey("mentioned_mobile_list"), "无 @ 名单时不应带该字段");
        assertFalse(payload.containsKey("at"));
    }

    @Test
    void wecomMentionLivesInsideText() {
        Map<String, Object> payload = text(wecom(), "标题", "正文", List.of("@all"));

        assertEquals(List.of("@all"), textNode(payload).get("mentioned_mobile_list"));
        assertFalse(payload.containsKey("at"), "企微的 @ 不在顶层");
    }

    @Test
    void dingtalkMentionIsTopLevelAt() {
        Map<String, Object> payload = text(dingtalk(), "标题", "正文", List.of("13800000000"));

        // 放错位置不会报错，只会安静地不 @ 到人，所以两种类型都要断言
        assertFalse(textNode(payload).containsKey("mentioned_mobile_list"), "钉钉的 @ 不放在 text 里");
        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) payload.get("at");
        assertEquals(List.of("13800000000"), at.get("atMobiles"));
        assertEquals(false, at.get("isAtAll"));
    }

    @Test
    void dingtalkAtAllUsesFlagNotMobileList() {
        Map<String, Object> payload = text(dingtalk(), "标题", "正文", List.of("@all"));

        @SuppressWarnings("unchecked")
        Map<String, Object> at = (Map<String, Object>) payload.get("at");
        assertEquals(true, at.get("isAtAll"));
        assertEquals(List.of(), at.get("atMobiles"));
    }

    @Test
    void contentTruncatedBeforeSend() {
        Map<String, Object> payload = text(wecom(), "标题", "长".repeat(2000), List.of());

        String content = (String) textNode(payload).get("content");
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= 1900);
        assertTrue(content.contains("已截断"));
    }

    /* ---------- markdown 形态 ---------- */

    @Test
    void markdownPayloadCarriesBothKeyNames() {
        Map<String, Object> payload = NotifyService.markdownPayload("标题", "正文");

        assertEquals("markdown", payload.get("msgtype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> md = (Map<String, Object>) payload.get("markdown");
        // 企微读 content，钉钉读 title+text，一份 body 两边都放才都能发出
        assertEquals("标题\n\n正文", md.get("content"));
        assertEquals("标题\n\n正文", md.get("text"));
        assertEquals("标题", md.get("title"));
    }

    @Test
    void markdownAllowsMoreThanText() {
        // markdown 上限 4096（留余量取 3900），比 text 的 1900 能装的内容多一倍
        String longContent = "长".repeat(4000);
        Map<String, Object> payload = NotifyService.markdownPayload("标题", longContent);

        @SuppressWarnings("unchecked")
        String md = (String) ((Map<String, Object>) payload.get("markdown")).get("content");
        int bytes = md.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 3900, "不得超过 markdown 上限，实际 " + bytes);
        assertTrue(bytes > 1900, "应能装下超过 text 上限的内容，实际 " + bytes);
    }

    /* ---------- 文件形态的辅助逻辑 ---------- */

    @Test
    void uploadUrlDerivedFromSendUrl() {
        // 上传接口和发送接口是同一个 key 的不同路径，从 send 地址推导出来
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/upload_media?key=abc123&type=file",
                NotifyService.uploadUrl("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123"));
        // 非企微 send 地址推不出来
        assertNull(NotifyService.uploadUrl("https://oapi.dingtalk.com/robot/send?access_token=abc"));
        assertNull(NotifyService.uploadUrl(null));
        assertNull(NotifyService.uploadUrl(""));
    }

    @Test
    void onlyWecomSupportsFile() {
        assertTrue(wecom().supportsFile());
        assertFalse(dingtalk().supportsFile(), "钉钉自定义机器人 webhook 发不了文件");
    }

    @Test
    void defaultFileNameIsSafe() {
        String name = NotifyService.defaultFileName("【AgentFlow 晨报】中台研发部/日报");

        // 文件名不能带路径分隔符等非法字符
        assertFalse(name.contains("/"));
        assertFalse(name.contains("【"));
        assertTrue(name.endsWith(".md"));
        assertTrue(name.contains(String.valueOf(java.time.LocalDate.now().getYear())));
        // 空标题也要能兜出一个可用名字
        assertTrue(NotifyService.defaultFileName(null).startsWith("AgentFlow"));
        assertTrue(NotifyService.defaultFileName("   ").startsWith("AgentFlow"));
    }

    /* ---------- 形态解析 ---------- */

    @Test
    void modeParseFallsBackToText() {
        assertEquals(NotifyMode.TEXT, NotifyMode.parse("text"));
        assertEquals(NotifyMode.MARKDOWN, NotifyMode.parse("markdown"));
        assertEquals(NotifyMode.FILE, NotifyMode.parse("FILE"));
        assertEquals(NotifyMode.MARKDOWN, NotifyMode.parse(" Markdown "));
        // 认不出来/缺失一律回退纯文本，保证老部署行为不变
        assertEquals(NotifyMode.TEXT, NotifyMode.parse("飞书"));
        assertEquals(NotifyMode.TEXT, NotifyMode.parse(""));
        assertEquals(NotifyMode.TEXT, NotifyMode.parse(null));
    }
}
