package com.agentflow.notify;

import com.agentflow.tool.ToolHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推送服务：把消息发到一个或多个群机器人通道（企微 / 钉钉），支持三种形态
 * （纯文本 / markdown / 摘要+文件附件，见 {@link NotifyMode}）。
 *
 * <p>推送目标来自「工作台 → 推送通道」里<b>选中</b>的通道（可多选）；
 * 界面里一条通道都没配时，回退到 .env 的 {@code agentflow.notify.webhook}，老配置不会失效。
 *
 * <p>已实测的群机器人行为，必须自己兜住：
 * <ul>
 *   <li><b>成败只看响应体 errcode</b>——HTTP 状态恒为 200，key 失效（93000）、
 *       正文结构错误（44004）、文件超限（40006）都藏在 errcode 里；只判 HTTP 会把失败记成「已推送」；</li>
 *   <li><b>超长不报错，而是投递时静默截断</b>——实测 1MB 正文仍返回 errcode 0，
 *       所以得自己按 UTF-8 字节截断并显式标注，否则读者不知道内容不全
 *       （企微按字节算长度，中文 3 字节/字）；</li>
 *   <li><b>文件要两步</b>——先 webhook/upload_media 拿 media_id，再发 msgtype=file；
 *       media_id 3 天有效，所以现传现发、不做缓存。</li>
 * </ul>
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 企微 text 正文上限 2048 字节、markdown 4096 字节（按字节而非字符），各留余量给截断提示 */
    private static final int MAX_BYTES_TEXT = 1900;
    private static final int MAX_BYTES_MARKDOWN = 3900;
    /** 实测 21MB 被企微以 40006 拒收，文档值为 20MB，取文档值上限 */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** 文件模式下群里那条摘要留多长（够看清是什么事，细节交给附件） */
    private static final int SUMMARY_BYTES = 600;
    private static final String TRUNCATED = "\n…（内容过长已截断，完整结果见 AgentFlow 工作台）";

    private final RestClient restClient;
    private final NotifyChannelStore store;
    private final String envWebhook;
    private final List<String> mentionMobiles;

    public NotifyService(ToolHttpClient toolHttpClient, NotifyChannelStore store,
                         @Value("${agentflow.notify.webhook:}") String webhookUrl,
                         @Value("${agentflow.notify.mention:}") String mention) {
        this.restClient = toolHttpClient.restClient();
        this.store = store;
        this.envWebhook = webhookUrl == null ? "" : webhookUrl.trim();
        this.mentionMobiles = parseMention(mention);
    }

    public boolean isConfigured() {
        return !effectiveChannels().isEmpty();
    }

    /**
     * 生效的推送目标：界面配了通道就用<b>选中</b>的那些；一条都没配时回退到 .env。
     * 界面配了但一个都没勾选 = 明确不推，此时不回退 .env（否则「取消勾选」这个动作失效）。
     */
    public List<NotifyChannel> effectiveChannels() {
        List<NotifyChannel> all = store.list();
        if (!all.isEmpty()) {
            List<String> selected = store.selected();
            return all.stream().filter(ch -> selected.contains(ch.name())).toList();
        }
        if (envWebhook.isEmpty()) {
            return List.of();
        }
        return List.of(new NotifyChannel("（来自 .env）",
                NotifyChannel.detectType(envWebhook), envWebhook, null));
    }

    /** 常规推送（纯文本），不 @ 人。推给所有选中通道，只要有一个成功就返回 true */
    public boolean send(String title, String content) {
        return push(NotifyMode.TEXT, title, content, false, null);
    }

    /**
     * 告警推送：@ 到 {@code agentflow.notify.mention} 配置的值班人。
     * 与 {@link #send} 分开是因为晨报这类例行推送不该天天 @所有人，而告警不 @ 就会被淹掉。
     */
    public boolean sendAlert(String title, String content) {
        return push(NotifyMode.TEXT, title, content, true, null);
    }

    /**
     * 按指定形态推送。多通道下<b>只要有一个通道成功就返回 true</b>（部分成功不该记为失败）。
     *
     * @param fileName 文件模式下附件名（null 则由标题推导）
     */
    public boolean push(NotifyMode mode, String title, String content, boolean alert, String fileName) {
        List<NotifyChannel> targets = effectiveChannels();
        if (targets.isEmpty()) {
            log.info("没有选中的推送通道，跳过推送");
            return false;
        }
        NotifyMode wanted = mode == null ? NotifyMode.TEXT : mode;
        boolean anyOk = false;
        for (NotifyChannel ch : targets) {
            try {
                if (pushOne(ch, wanted, title, content, alert, fileName)) {
                    anyOk = true;
                }
            } catch (Throwable t) {
                // 这里是「通知边界」：推送是任务的收尾动作，绝不能反过来把任务本身打挂。
                // 捕获 Throwable 而非 Exception 是因为踩过一次——缺类引发的 NoClassDefFoundError
                // 是 Error 不是 Exception，逃出去后让整个晨报任务 500、执行记录都没落库。
                log.error("通道「{}」推送异常（已忽略，不影响任务结果）：{}", ch.name(), t.toString());
            }
        }
        return anyOk;
    }

    private boolean pushOne(NotifyChannel ch, NotifyMode mode, String title, String content,
                            boolean alert, String fileName) {
        // 告警强制纯文本：@ 值班人只有文本消息支持得可靠（企微 markdown 带不上手机号，
        // file 消息更是没有 @ 字段），而告警卡片本来就该短
        NotifyMode effective = alert ? NotifyMode.TEXT : mode;

        if (effective == NotifyMode.FILE) {
            if (ch.supportsFile()) {
                return sendFileWithSummary(ch, title, content, fileName);
            }
            log.info("通道「{}」不支持文件推送（{}），降级为 markdown", ch.name(), ch.typeName());
            effective = NotifyMode.MARKDOWN;
        }
        if (effective == NotifyMode.MARKDOWN) {
            return post(ch.name(), ch.url(), markdownPayload(title, content));
        }
        List<String> mentions = alert ? mentionMobiles : List.of();
        return post(ch.name(), ch.url(), textPayload(ch, title, content, mentions, MAX_BYTES_TEXT));
    }

    /**
     * 文件模式：先发一条摘要文本（群里不点附件也能看清是什么事），再发附件本体。
     * 上传失败则只留摘要——宁可推个摘要，也不能整条推送什么都不发。
     */
    private boolean sendFileWithSummary(NotifyChannel ch, String title, String content, String fileName) {
        String name = fileName == null || fileName.isBlank() ? defaultFileName(title) : fileName.trim();
        String summary = truncateBytes(nullToEmpty(content), SUMMARY_BYTES)
                + "\n\n（完整内容见附件 " + name + "）";
        boolean summaryOk = post(ch.name(), ch.url(),
                textPayload(ch, title, summary, List.of(), MAX_BYTES_TEXT));

        byte[] bytes = nullToEmpty(content).getBytes(StandardCharsets.UTF_8);
        String mediaId = uploadMedia(ch, name, bytes);
        if (mediaId == null) {
            log.warn("通道「{}」附件上传失败，本次只推送摘要", ch.name());
            return summaryOk;
        }
        boolean fileOk = post(ch.name(), ch.url(),
                Map.of("msgtype", "file", "file", Map.of("media_id", mediaId)));
        return summaryOk || fileOk;
    }

    /**
     * 上传文件拿 media_id。webhook/upload_media 与 send 是同一个 key、不同路径，
     * 由 send 地址推导出来，省得让用户再填一次地址。
     */
    private String uploadMedia(NotifyChannel ch, String fileName, byte[] bytes) {
        String uploadUrl = uploadUrl(ch.url());
        if (uploadUrl == null) {
            log.warn("通道「{}」地址不是企微群机器人 send 地址，无法推导上传接口", ch.name());
            return null;
        }
        if (bytes.length > MAX_FILE_BYTES) {
            log.warn("通道「{}」附件 {} 字节，超过上限 {}，放弃上传", ch.name(), bytes.length, MAX_FILE_BYTES);
            return null;
        }
        try {
            // 不用 MultipartBodyBuilder：它属于 WebFlux 那套，会引用 reactivestreams.Publisher，
            // 本项目只有 spring-web，类加载就会 NoClassDefFoundError。
            // 用 MultiValueMap + Resource 走 AllEncompassingFormHttpMessageConverter 的 multipart 分支。
            ByteArrayResource part = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("media", part);
            String resp = restClient.post()
                    .uri(uploadUrl)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode node = MAPPER.readTree(resp == null ? "{}" : resp);
            if (node.path("errcode").asInt(-1) != 0) {
                log.warn("通道「{}」附件上传被拒绝：errcode={} errmsg={}",
                        ch.name(), node.path("errcode").asInt(), node.path("errmsg").asText(""));
                return null;
            }
            String mediaId = node.path("media_id").asText("");
            return mediaId.isEmpty() ? null : mediaId;
        } catch (Exception ex) {
            log.warn("通道「{}」附件上传失败：{}", ch.name(), ex.getMessage());
            return null;
        }
    }

    /** 由 send 地址推导 upload_media 地址；非企微 send 地址返回 null */
    static String uploadUrl(String sendUrl) {
        if (sendUrl == null || !sendUrl.contains("/webhook/send")) {
            return null;
        }
        return sendUrl.replace("/webhook/send", "/webhook/upload_media") + "&type=file";
    }

    static String defaultFileName(String title) {
        String cleaned = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|【】\\r\\n]", "").trim();
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40);
        }
        return (cleaned.isEmpty() ? "AgentFlow" : cleaned) + "-"
                + java.time.LocalDate.now() + ".md";
    }

    private boolean post(String channelName, String url, Map<String, Object> body) {
        if (url == null || url.isBlank()) {
            log.warn("通道「{}」未配置 Webhook 地址", channelName);
            return false;
        }
        try {
            String resp = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (!accepted(resp)) {
                log.warn("通道「{}」被拒绝，请检查 Webhook 是否有效", channelName);
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.warn("通道「{}」推送失败：{}", channelName, ex.getMessage());
            return false;
        }
    }

    /** 单通道测试推送：走文本形态（不影响选中状态，也不改配置） */
    public boolean test(NotifyChannel channel) {
        if (channel == null || channel.url() == null || channel.url().isBlank()) {
            return false;
        }
        return post(channel.name(), channel.url(),
                textPayload(channel, "【AgentFlow 推送通道测试】", "如果你看到这条消息，说明该通道可以正常推送。", List.of(), MAX_BYTES_TEXT));
    }

    /** 群机器人 text 消息体（结构错误会被 errcode 44004 拒绝，故单独成纯函数便于验证） */
    static Map<String, Object> textPayload(NotifyChannel channel, String title, String content,
                                            List<String> mentions, int maxBytes) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("content", truncateBytes(title + "\n\n" + nullToEmpty(content), maxBytes));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", "text");
        body.put("text", text);
        applyMention(body, text, channel, mentions);
        return body;
    }

    /**
     * markdown 消息体。企微用 content，钉钉用 title + text（两者各自的必填字段），
     * 多余的键两边都会忽略，所以一份 body 同时兼容。
     */
    static Map<String, Object> markdownPayload(String title, String content) {
        String md = truncateBytes(title + "\n\n" + nullToEmpty(content), MAX_BYTES_MARKDOWN);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("content", md);
        node.put("text", md);
        node.put("title", title == null ? "AgentFlow" : title);
        return Map.of("msgtype", "markdown", "markdown", node);
    }

    /**
     * @ 人的字段位置两种机器人不一样：企微在 text 里放 mentioned_mobile_list，
     * 钉钉是顶层 at 对象（手机号列表 + isAtAll）。放错位置不会报错，只是安静地不 @ 到人。
     */
    private static void applyMention(Map<String, Object> body, Map<String, Object> text,
                                     NotifyChannel channel, List<String> mentions) {
        if (mentions == null || mentions.isEmpty()) {
            return;
        }
        if (channel != null && channel.dingtalk()) {
            boolean atAll = mentions.stream().anyMatch("@all"::equalsIgnoreCase);
            Map<String, Object> at = new LinkedHashMap<>();
            at.put("atMobiles", atAll ? List.of() : mentions);
            at.put("isAtAll", atAll);
            body.put("at", at);
        } else {
            text.put("mentioned_mobile_list", mentions);
        }
    }

    /**
     * 企微/钉钉都用「HTTP 200 + 响应体 errcode」表达结果，errcode 非 0 即失败。
     * 响应非 JSON 时按成功处理，兼容自定义 Webhook（HTTP 层错误已由 retrieve() 抛出）。
     */
    static boolean accepted(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            int errcode = node.path("errcode").asInt(0);
            if (errcode != 0) {
                log.warn("Webhook 被拒绝：errcode={} errmsg={}", errcode, node.path("errmsg").asText(""));
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.debug("Webhook 响应非 JSON，按成功处理：{}", truncateBytes(body, 200));
            return true;
        }
    }

    /** 按 UTF-8 字节数截断，且不切断多字节字符（否则尾部会渲染成乱码方块） */
    static String truncateBytes(String s, int maxBytes) {
        if (s == null) {
            return "";
        }
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        int budget = Math.max(0, maxBytes - TRUNCATED.getBytes(StandardCharsets.UTF_8).length);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int len = utf8Length(cp);
            if (used + len > budget) {
                break;
            }
            sb.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        return sb.append(TRUNCATED).toString();
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /** 解析提及名单：逗号/分号/空白分隔的手机号，或 @all */
    static List<String> parseMention(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，;；\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
