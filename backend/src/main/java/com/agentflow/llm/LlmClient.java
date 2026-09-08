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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final boolean enabled;
    private final HttpClient httpClient;

    public LlmClient(@Value("${agentflow.llm.base-url}") String baseUrl,
                     @Value("${agentflow.llm.api-key:}") String apiKey,
                     @Value("${agentflow.llm.model}") String model,
                     @Value("${agentflow.llm.proxy.host:}") String proxyHost,
                     @Value("${agentflow.llm.proxy.port:0}") int proxyPort) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.enabled = !this.apiKey.isEmpty();

        HttpClient.Builder hb = HttpClient.newBuilder();
        if (this.enabled && proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            hb.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.httpClient = hb.build();
        // RestClient 与流式请求共用同一个 HttpClient，代理等出站配置保持一致
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
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
        return call(systemPrompt, userPrompt, false, false);
    }

    public String reason(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, true, false);
    }

    /** 要求模型输出 JSON 对象（DeepSeek 兼容 OpenAI response_format），用于规划/意图抽取 */
    public String chatJson(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, false, true);
    }

    /** 流式生成：每个增量片段回调 onDelta，返回完整文本。中断时返回已累积部分 */
    public String chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        if (!enabled) {
            throw new IllegalStateException("未配置 DeepSeek API Key");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", true);
        // 流式主要用于长文交付物，偏低温度保证格式与事实遵循
        body.put("temperature", 0.5);
        body.put("max_tokens", 2048);

        StringBuilder acc = new StringBuilder();
        String lastRawLine = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("DeepSeek 流式响应异常 HTTP " + resp.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    lastRawLine = line;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                    JsonNode delta = mapper.readTree(payload)
                            .path("choices").path(0).path("delta").path("content");
                    String piece = delta.asText("");
                    if (!piece.isEmpty()) {
                        acc.append(piece);
                        onDelta.accept(piece);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("DeepSeek 流式调用中断: {}", ex.getMessage());
            if (acc.isEmpty()) {
                throw new RuntimeException("DeepSeek 流式调用失败: " + ex.getMessage(), ex);
            }
        }
        if (acc.isEmpty()) {
            // 200 但无任何内容增量：按失败处理，让上层走非流式重试
            log.warn("DeepSeek 流式响应无内容，最后一条原始行: {}", lastRawLine);
            throw new IllegalStateException("DeepSeek 流式响应无内容");
        }
        return acc.toString();
    }

    private String call(String systemPrompt, String userPrompt, boolean reasoning) {
        return call(systemPrompt, userPrompt, reasoning, false);
    }

    private String call(String systemPrompt, String userPrompt, boolean reasoning, boolean jsonMode) {
        if (!enabled) {
            throw new IllegalStateException("未配置 DeepSeek API Key");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", false);
        body.put("temperature", jsonMode ? 0.2 : (reasoning ? 0.6 : 0.9));
        body.put("max_tokens", 2048);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
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
