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

/**
 * LLM 客户端：双协议接入，运行时可切换（工作台「模型接入」配置，存 SQLite，改完即生效）。
 * provider=anthropic：Anthropic Messages 协议（/v1/messages，x-api-key 头）；
 * provider=openai：OpenAI 兼容协议（/chat/completions，Bearer 头，DeepSeek/GPT/本地网关等）。
 * 未做运行时配置时回退 .env / application.yml 的默认值。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    /* .env / yml 默认值（运行时配置清除后回退用） */
    private final String envBaseUrl;
    private final String envApiKey;
    private final String envModel;

    /* 当前生效配置（DB 覆盖后刷新） */
    private volatile String provider = "openai";
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String model;
    private volatile boolean enabled;

    public LlmClient(@Value("${agentflow.llm.base-url}") String baseUrl,
                     @Value("${agentflow.llm.api-key:}") String apiKey,
                     @Value("${agentflow.llm.model}") String model,
                     @Value("${agentflow.llm.provider:openai}") String provider,
                     @Value("${agentflow.llm.proxy.host:}") String proxyHost,
                     @Value("${agentflow.llm.proxy.port:0}") int proxyPort) {
        this.envBaseUrl = trimUrl(baseUrl);
        this.envApiKey = apiKey == null ? "" : apiKey.trim();
        this.envModel = model;
        applyConfig(provider, this.envBaseUrl, this.envApiKey, this.envModel);

        HttpClient.Builder hb = HttpClient.newBuilder();
        if (!this.envApiKey.isEmpty() && proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            hb.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.httpClient = hb.build();
        // RestClient 与流式请求共用同一个 HttpClient，代理等出站配置保持一致
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    /** 应用运行时配置（任意字段非法时抛异常，不落库） */
    public void applyConfig(String provider, String baseUrl, String apiKey, String model) {
        if (provider == null || (!"openai".equals(provider) && !"anthropic".equals(provider))) {
            throw new IllegalArgumentException("接口类型仅支持 openai / anthropic");
        }
        if (baseUrl == null || !baseUrl.trim().startsWith("http")) {
            throw new IllegalArgumentException("请求地址必须以 http(s):// 开头");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }
        String key = apiKey == null ? "" : apiKey.trim();
        this.provider = provider;
        this.baseUrl = trimUrl(baseUrl);
        this.apiKey = key;
        this.model = model.trim();
        this.enabled = !key.isEmpty();
    }

    /** 恢复 .env / yml 默认配置 */
    public void resetToEnv() {
        applyConfig("openai", envBaseUrl, envApiKey, envModel);
    }

    private static String trimUrl(String u) {
        return u == null ? "" : u.trim().replaceAll("/+$", "");
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

    public String getProvider() {
        return provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    private String messagesUrl(String base) {
        return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    /* ---------- 对外入口 ---------- */

    public String chat(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, false, false);
    }

    public String reason(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, true, false);
    }

    /** 要求模型输出 JSON 对象（OpenAI 协议走 response_format，Anthropic 协议靠提示词约束） */
    public String chatJson(String systemPrompt, String userPrompt) {
        return call(systemPrompt, userPrompt, false, true);
    }

    /** 无状态测试：用给定参数直连一次最小对话，不改动当前生效配置 */
    public String testCall(String provider, String baseUrl, String apiKey, String model) {
        return "anthropic".equals(provider)
                ? callAnthropic(baseUrl.trim(), apiKey.trim(), model.trim(),
                        "你是连通性测试助手。", "请只回复两个字：正常", false)
                : callOpenai(baseUrl.trim(), apiKey.trim(), model.trim(),
                        "你是连通性测试助手。", "请只回复两个字：正常", false, false);
    }

    /* ---------- 流式生成 ---------- */

    /** 流式生成：每个增量片段回调 onDelta，返回完整文本。中断时返回已累积部分 */
    public String chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        if (!enabled) {
            throw new IllegalStateException("未配置 API Key");
        }
        return "anthropic".equals(provider)
                ? streamAnthropic(systemPrompt, userPrompt, onDelta)
                : streamOpenai(systemPrompt, userPrompt, onDelta);
    }

    private String streamOpenai(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", true);
        // 流式主要用于长文交付物，偏低温度保证格式与事实遵循
        body.put("temperature", 0.5);
        body.put("max_tokens", 8192);

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
                throw new IllegalStateException("流式响应异常 HTTP " + resp.statusCode());
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
            log.warn("LLM 流式调用中断: {}", ex.getMessage());
            if (acc.isEmpty()) {
                throw new RuntimeException("LLM 流式调用失败: " + ex.getMessage(), ex);
            }
        }
        if (acc.isEmpty()) {
            // 200 但无任何内容增量：按失败处理，让上层走非流式重试
            log.warn("LLM 流式响应无内容，最后一条原始行: {}", lastRawLine);
            throw new IllegalStateException("LLM 流式响应无内容");
        }
        return acc.toString();
    }

    private String streamAnthropic(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
        body.put("stream", true);
        body.put("temperature", 0.5);
        body.put("max_tokens", 8192);

        StringBuilder acc = new StringBuilder();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl(baseUrl)))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("流式响应异常 HTTP " + resp.statusCode()
                        + " " + new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    JsonNode evt = mapper.readTree(payload);
                    // 内容增量事件：content_block_delta → delta.text
                    if ("content_block_delta".equals(evt.path("type").asText())) {
                        String piece = evt.path("delta").path("text").asText("");
                        if (!piece.isEmpty()) {
                            acc.append(piece);
                            onDelta.accept(piece);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("LLM 流式调用中断: {}", ex.getMessage());
            if (acc.isEmpty()) {
                throw new RuntimeException("LLM 流式调用失败: " + ex.getMessage(), ex);
            }
        }
        if (acc.isEmpty()) {
            throw new IllegalStateException("LLM 流式响应无内容");
        }
        return acc.toString();
    }

    /* ---------- 非流式 ---------- */

    private String call(String systemPrompt, String userPrompt, boolean reasoning, boolean jsonMode) {
        if (!enabled) {
            throw new IllegalStateException("未配置 API Key");
        }
        return "anthropic".equals(provider)
                ? callAnthropic(baseUrl, apiKey, model, systemPrompt, userPrompt, jsonMode)
                : callOpenai(baseUrl, apiKey, model, systemPrompt, userPrompt, reasoning, jsonMode);
    }

    private String callOpenai(String baseUrl, String apiKey, String model,
                              String systemPrompt, String userPrompt, boolean reasoning, boolean jsonMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", false);
        body.put("temperature", jsonMode ? 0.2 : (reasoning ? 0.6 : 0.9));
        body.put("max_tokens", 8192);
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
            log.warn("LLM 调用失败: {}", ex.getMessage());
            throw new RuntimeException("LLM 调用失败: " + ex.getMessage(), ex);
        }
    }

    private String callAnthropic(String baseUrl, String apiKey, String model,
                                 String systemPrompt, String userPrompt, boolean jsonMode) {
        String system = systemPrompt;
        if (jsonMode) {
            // Anthropic 协议没有 response_format，靠提示词约束 JSON 输出
            system = systemPrompt + "\n只输出一个 JSON 对象，不要输出任何其他文本或代码块标记。";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
        body.put("stream", false);
        body.put("temperature", jsonMode ? 0.2 : 0.9);
        body.put("max_tokens", 8192);

        try {
            String json = restClient.post()
                    .uri(messagesUrl(baseUrl))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(json);
            // content 是分块数组，拼接其中全部 text 块
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText(""));
                    }
                }
                return sb.toString();
            }
            JsonNode err = root.path("error").path("message");
            if (!err.isMissingNode()) {
                throw new IllegalStateException(err.asText());
            }
            return "";
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("LLM 调用失败: {}", ex.getMessage());
            throw new RuntimeException("LLM 调用失败: " + ex.getMessage(), ex);
        }
    }
}
