package com.agentflow.notify;

import com.agentflow.tool.ToolHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 通用 Webhook 推送：企微/钉钉机器人同构（msgtype=text），
 * 未配置 URL 时跳过（只落库不推送）。内容超长截断到 2000 字。
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final int MAX_CONTENT = 2000;

    private final RestClient restClient;
    private final String webhookUrl;

    public NotifyService(ToolHttpClient toolHttpClient,
                         @Value("${agentflow.notify.webhook:}") String webhookUrl) {
        this.restClient = toolHttpClient.restClient();
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    public boolean isConfigured() {
        return !webhookUrl.isEmpty();
    }

    /** 推送文本消息；未配置或失败返回 false（失败只记日志，不影响主流程） */
    public boolean send(String title, String content) {
        if (!isConfigured()) {
            log.info("未配置推送 Webhook，跳过推送：{}", title);
            return false;
        }
        String text = title + "\n\n" + (content == null ? "" : content);
        if (text.length() > MAX_CONTENT) {
            text = text.substring(0, MAX_CONTENT) + "…";
        }
        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msgtype", "text", "text", Map.of("content", text)))
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception ex) {
            log.warn("Webhook 推送失败：{}", ex.getMessage());
            return false;
        }
    }
}
