package com.agentflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean enabled;

    public LlmClient(@Value("${agentflow.llm.base-url}") String baseUrl,
                     @Value("${agentflow.llm.api-key:}") String apiKey,
                     @Value("${agentflow.llm.model}") String model,
                     @Value("${agentflow.llm.proxy.host:}") String proxyHost,
                     @Value("${agentflow.llm.proxy.port:0}") int proxyPort) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.enabled = !this.apiKey.isEmpty();

        RestClient.Builder builder = RestClient.builder();
        if (this.enabled && proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)))
                    .build();
            builder.requestFactory(new JdkClientHttpRequestFactory(httpClient));
        }
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String chat(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, false);
    }

    public String reason(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, true);
    }

    private String call(String systemPrompt, String userPrompt, boolean reasoning) {
        if (!enabled) {
            throw new IllegalStateException("未配置 DeepSeek API Key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", false);
        body.put("temperature", reasoning ? 0.6 : 0.9);
        body.put("max_tokens", 2048);
        if (reasoning) {
            body.put("thinking", Map.of("type", "enabled"));
        }

        try {
            String json = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(json);
            JsonNode message = root.path("choices").path(0).path("message");
            if (reasoning) {
                String rc = message.path("reasoning_content").asText("");
                if (!rc.isBlank()) {
                    return rc;
                }
            }
            return message.path("content").asText("");
        } catch (Exception ex) {
            log.warn("DeepSeek 调用失败: {}", ex.getMessage());
            throw new RuntimeException("DeepSeek 调用失败: " + ex.getMessage(), ex);
        }
    }
}
