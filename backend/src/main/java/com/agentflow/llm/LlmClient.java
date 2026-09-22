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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LLM 客户端：双协议接入，运行时可切换（工作台「模型接入」配置，存 SQLite，改完即生效）。
 * provider=anthropic：Anthropic Messages 协议（/v1/messages，x-api-key 头）；
 * provider=openai：OpenAI 兼容协议（/chat/completions，Bearer 头，DeepSeek/GPT/本地网关等）。
 * 未做运行时配置时回退 .env / application.yml 的默认值。
 *
 * <p>每次调用都会往 {@link LlmUsageStore} 落一行埋点（用途、token、耗时、成败），
 * 用途由调用方通过 {@code purpose} 参数显式给出——只有调用方知道这次是在规划还是在生成，
 * 从模型名或 prompt 内容反推既不可靠也没必要。任务归属走 {@link LlmContext}。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 埋点用途词表：前端按这套 key 做中文映射，新增用途要同步加映射 */
    public static final String PURPOSE_PLAN = "plan";
    public static final String PURPOSE_REACT = "react";
    public static final String PURPOSE_REASON = "reason";
    public static final String PURPOSE_GENERATE = "generate";
    public static final String PURPOSE_SUMMARIZE = "summarize";
    public static final String PURPOSE_MEMORY = "memory";
    public static final String PURPOSE_TEST = "test";
    public static final String PURPOSE_CHAT = "chat";
    public static final String PURPOSE_POSTMORTEM = "postmortem";

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final LlmUsageStore usageStore;
    private final boolean streamUsage;
    private final int readTimeoutMs;
    private final long streamIdleTimeoutMs;
    private final ScheduledExecutorService streamWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-stream-watchdog");
        t.setDaemon(true);
        return t;
    });

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
                     @Value("${agentflow.llm.proxy.port:0}") int proxyPort,
                     @Value("${agentflow.llm.stream-usage:false}") boolean streamUsage,
                     @Value("${agentflow.llm.connect-timeout-ms:10000}") int connectTimeoutMs,
                     @Value("${agentflow.llm.read-timeout-ms:180000}") int readTimeoutMs,
                     @Value("${agentflow.llm.stream-idle-timeout-ms:90000}") long streamIdleTimeoutMs,
                     LlmUsageStore usageStore) {
        this.envBaseUrl = trimUrl(baseUrl);
        this.envApiKey = apiKey == null ? "" : apiKey.trim();
        this.envModel = model;
        this.usageStore = usageStore;
        this.streamUsage = streamUsage;
        this.readTimeoutMs = Math.max(1000, readTimeoutMs);
        this.streamIdleTimeoutMs = Math.max(1000L, streamIdleTimeoutMs);
        applyConfig(provider, this.envBaseUrl, this.envApiKey, this.envModel);

        HttpClient.Builder hb = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, connectTimeoutMs)));
        if (!this.envApiKey.isEmpty() && proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            hb.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.httpClient = hb.build();
        // RestClient 与流式请求共用同一个 HttpClient，代理等出站配置保持一致。
        // 没有读超时时，上游网关挂住会永久占用引擎线程，进而拖垮整个线程池。
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(this.readTimeoutMs));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 流式读的空闲看门狗。{@code HttpRequest.timeout} 只覆盖到「拿到响应头」为止，
     * 之后若 TCP 还活着但不再吐数据，阻塞中的 {@code readLine} 不会自己超时，
     * 只能靠关闭响应体把它打断——否则一次卡死的生成会永久占住引擎线程。
     */
    private Watchdog openWatchdog(java.io.InputStream body) {
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        ScheduledFuture<?> task = streamWatchdog.scheduleWithFixedDelay(() -> {
            if (System.currentTimeMillis() - lastActivity.get() >= streamIdleTimeoutMs) {
                log.warn("LLM 流式响应空闲超过 {}ms，关闭连接中断本次生成", streamIdleTimeoutMs);
                try {
                    body.close();
                } catch (Exception ignore) {
                    // 关流只为打断阻塞读，失败也没有副作用
                }
            }
        }, streamIdleTimeoutMs, 1000L, TimeUnit.MILLISECONDS);
        return new Watchdog(task, lastActivity);
    }

    private record Watchdog(ScheduledFuture<?> task, AtomicLong lastActivity) {
        void touch() {
            lastActivity.set(System.currentTimeMillis());
        }

        void stop() {
            task.cancel(false);
        }
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

    /** .env 默认配置的模型名（页面下拉框“恢复默认”行的展示用） */
    public String getEnvModel() {
        return envModel;
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

    /** 是否向服务端请求流式 token 用量（部分网关不认识该参数，故默认关闭） */
    public boolean isStreamUsageRequested() {
        return streamUsage;
    }

    private String messagesUrl(String base) {
        return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    /* ---------- 对外入口 ---------- */

    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, PURPOSE_CHAT);
    }

    public String chat(String systemPrompt, String userPrompt, String purpose) {
        return call(systemPrompt, userPrompt, false, false, purpose);
    }

    public String reason(String systemPrompt, String userPrompt) {
        return reason(systemPrompt, userPrompt, PURPOSE_REASON);
    }

    public String reason(String systemPrompt, String userPrompt, String purpose) {
        return call(systemPrompt, userPrompt, true, false, purpose);
    }

    public String chatJson(String systemPrompt, String userPrompt) {
        return chatJson(systemPrompt, userPrompt, PURPOSE_PLAN);
    }

    /** 要求模型输出 JSON 对象（OpenAI 协议走 response_format，Anthropic 协议靠提示词约束） */
    public String chatJson(String systemPrompt, String userPrompt, String purpose) {
        return call(systemPrompt, userPrompt, false, true, purpose);
    }

    /** 无状态测试：用给定参数直连一次最小对话，不改动当前生效配置 */
    public String testCall(String provider, String baseUrl, String apiKey, String model) {
        if (provider == null || baseUrl == null || apiKey == null || model == null) {
            throw new IllegalArgumentException("测试连接需要填写接口类型、请求地址、API Key 与模型名");
        }
        boolean anthropic = "anthropic".equals(provider);
        long start = System.nanoTime();
        try {
            Answer answer = anthropic
                    ? callAnthropic(baseUrl.trim(), apiKey.trim(), model.trim(),
                            "你是连通性测试助手。", "请只回复两个字：正常", false)
                    : callOpenai(baseUrl.trim(), apiKey.trim(), model.trim(),
                            "你是连通性测试助手。", "请只回复两个字：正常", false, false);
            record(PURPOSE_TEST, provider, model.trim(), false, answer.usage(), start, true, null);
            return answer.text();
        } catch (RuntimeException ex) {
            record(PURPOSE_TEST, provider, model.trim(), false, null, start, false, ex.getMessage());
            throw ex;
        }
    }

    /* ---------- 流式生成 ---------- */

    /** 流式生成：每个增量片段回调 onDelta，返回完整文本。中断时返回已累积部分 */
    public String chatStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        return chatStream(systemPrompt, userPrompt, PURPOSE_GENERATE, onDelta);
    }

    public String chatStream(String systemPrompt, String userPrompt, String purpose,
                             Consumer<String> onDelta) {
        if (!enabled) {
            throw new IllegalStateException("未配置 API Key");
        }
        long start = System.nanoTime();
        String currentProvider = provider;
        String currentModel = model;
        try {
            Answer answer = "anthropic".equals(currentProvider)
                    ? streamAnthropic(systemPrompt, userPrompt, onDelta)
                    : streamOpenai(systemPrompt, userPrompt, onDelta);
            record(purpose, currentProvider, currentModel, true, answer.usage(), start, true, null);
            return answer.text();
        } catch (RuntimeException ex) {
            record(purpose, currentProvider, currentModel, true, null, start, false, ex.getMessage());
            throw ex;
        }
    }

    private Answer streamOpenai(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", true);
        // 流式主要用于长文交付物，偏低温度保证格式与事实遵循
        body.put("temperature", 0.5);
        body.put("max_tokens", 8192);
        if (streamUsage) {
            // 只有显式开启才请求：部分兼容网关不认这个字段，会直接 400
            body.put("stream_options", Map.of("include_usage", true));
        }

        StringBuilder acc = new StringBuilder();
        String lastRawLine = null;
        Usage reported = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("流式响应异常 HTTP " + resp.statusCode());
            }
            Watchdog watchdog = openWatchdog(resp.body());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    watchdog.touch();
                    if (!line.startsWith("data:")) continue;
                    lastRawLine = line;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                    JsonNode chunk = mapper.readTree(payload);
                    // 用量只出现在最后一个块里（choices 为空、usage 有值），别被前面的块带偏
                    Usage fromChunk = openaiUsage(chunk.path("usage"));
                    if (fromChunk != null) {
                        reported = fromChunk;
                    }
                    JsonNode delta = chunk.path("choices").path(0).path("delta").path("content");
                    String piece = delta.asText("");
                    if (!piece.isEmpty()) {
                        acc.append(piece);
                        onDelta.accept(piece);
                    }
                }
            } finally {
                watchdog.stop();
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
        // 服务端没报用量（默认情况）就按文本长度估算，并标记为估算值
        Usage usage = reported != null ? reported
                : new Usage(estimateTokens(systemPrompt + userPrompt), estimateTokens(acc.toString()), true);
        return new Answer(acc.toString(), usage);
    }

    private Answer streamAnthropic(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
        body.put("stream", true);
        body.put("temperature", 0.5);
        body.put("max_tokens", 8192);

        StringBuilder acc = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        boolean sawUsage = false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl(baseUrl)))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> resp = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("流式响应异常 HTTP " + resp.statusCode()
                        + " " + new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
            }
            Watchdog watchdog = openWatchdog(resp.body());
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    watchdog.touch();
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) continue;
                    JsonNode evt = mapper.readTree(payload);
                    String type = evt.path("type").asText();
                    // Anthropic 协议必定上报用量：message_start 给输入，message_delta 给输出累计值
                    if ("message_start".equals(type)) {
                        int in = evt.path("message").path("usage").path("input_tokens").asInt(0);
                        if (in > 0) {
                            promptTokens = in;
                            sawUsage = true;
                        }
                    } else if ("message_delta".equals(type)) {
                        int out = evt.path("usage").path("output_tokens").asInt(0);
                        if (out > 0) {
                            completionTokens = out;
                            sawUsage = true;
                        }
                    }
                    // 内容增量事件：content_block_delta → delta.text
                    if ("content_block_delta".equals(type)) {
                        String piece = evt.path("delta").path("text").asText("");
                        if (!piece.isEmpty()) {
                            acc.append(piece);
                            onDelta.accept(piece);
                        }
                    }
                }
            } finally {
                watchdog.stop();
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
        Usage usage = sawUsage
                ? new Usage(promptTokens, completionTokens, false)
                : new Usage(estimateTokens(systemPrompt + userPrompt), estimateTokens(acc.toString()), true);
        return new Answer(acc.toString(), usage);
    }

    /* ---------- 非流式 ---------- */

    private String call(String systemPrompt, String userPrompt, boolean reasoning, boolean jsonMode,
                        String purpose) {
        if (!enabled) {
            throw new IllegalStateException("未配置 API Key");
        }
        String currentProvider = provider;
        String currentModel = model;
        long start = System.nanoTime();
        try {
            Answer answer = "anthropic".equals(currentProvider)
                    ? callAnthropic(baseUrl, apiKey, currentModel, systemPrompt, userPrompt, jsonMode)
                    : callOpenai(baseUrl, apiKey, currentModel, systemPrompt, userPrompt, reasoning, jsonMode);
            if (answer.usage() != null && answer.usage().estimated()) {
                // 服务端没给 usage：按输入/输出文本估算，并如实标记（估算值不冒充精确值）
                log.debug("LLM 响应未带 usage，已按文本长度估算 token 数");
            }
            record(purpose, currentProvider, currentModel, false, answer.usage(), start, true, null);
            return answer.text();
        } catch (RuntimeException ex) {
            // 失败同样计入耗时与失败率
            record(purpose, currentProvider, currentModel, false, null, start, false, ex.getMessage());
            throw ex;
        }
    }

    private Answer callOpenai(String baseUrl, String apiKey, String model,
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
            String text;
            if (reasoning) {
                String rc = message.path("reasoning_content").asText("");
                text = rc.isBlank() ? message.path("content").asText("") : rc;
            } else {
                text = message.path("content").asText("");
            }
            Usage usage = openaiUsage(root.path("usage"));
            return new Answer(text, usage != null ? usage
                    : new Usage(estimateTokens(systemPrompt + userPrompt), estimateTokens(text), true));
        } catch (Exception ex) {
            log.warn("LLM 调用失败: {}", ex.getMessage());
            throw new RuntimeException("LLM 调用失败: " + ex.getMessage(), ex);
        }
    }

    private Answer callAnthropic(String baseUrl, String apiKey, String model,
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
            JsonNode usageNode = root.path("usage");
            Usage usage = usageNode.isObject()
                    ? new Usage(usageNode.path("input_tokens").asInt(0),
                            usageNode.path("output_tokens").asInt(0), false)
                    : null;
            // content 是分块数组，拼接其中全部 text 块
            JsonNode content = root.path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText(""));
                    }
                }
                String text = sb.toString();
                return new Answer(text, usage != null ? usage
                        : new Usage(estimateTokens(system + userPrompt), estimateTokens(text), true));
            }
            JsonNode err = root.path("error").path("message");
            if (!err.isMissingNode()) {
                throw new IllegalStateException(err.asText());
            }
            return new Answer("", usage);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("LLM 调用失败: {}", ex.getMessage());
            throw new RuntimeException("LLM 调用失败: " + ex.getMessage(), ex);
        }
    }

    /* ---------- 埋点 ---------- */

    private void record(String purpose, String providerName, String modelName, boolean stream,
                        Usage usage, long startNanos, boolean ok, String error) {
        try {
            int prompt = usage == null ? 0 : usage.prompt();
            int completion = usage == null ? 0 : usage.completion();
            usageStore.record(new LlmUsageStore.LlmCall(purpose, providerName, modelName,
                    prompt, completion, prompt + completion,
                    usage == null || usage.estimated(),
                    (System.nanoTime() - startNanos) / 1_000_000L,
                    ok, error, stream, LlmContext.taskId()));
        } catch (Throwable t) {
            // 埋点是旁路：任何情况下都不能因为它让 LLM 调用失败
            log.debug("LLM 埋点写入异常（已忽略）：{}", t.toString());
        }
    }

    /** OpenAI 兼容协议的 usage 节点；不存在时返回 null 交由调用方估算 */
    private static Usage openaiUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        int prompt = node.path("prompt_tokens").asInt(0);
        int completion = node.path("completion_tokens").asInt(0);
        if (prompt == 0 && completion == 0) {
            return null;
        }
        return new Usage(prompt, completion, false);
    }

    /**
     * 按文本估算 token 数：中日韩字符约 1 字 1 token，ASCII 约 4 字符 1 token。
     * 比笼统的「字符数 / 4」更接近真实值——这个项目的 prompt 与产物大量是中文，
     * 用 ASCII 比例估算会低报三四倍，那样成本看板还不如不做。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int wide = 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x80) {
                ascii++;
            } else {
                wide++;
            }
        }
        return Math.max(1, (int) Math.round(wide + ascii / 4.0));
    }

    /** 服务端上报的用量；estimated=true 表示由文本长度估算而非服务端返回 */
    private record Usage(int prompt, int completion, boolean estimated) {
    }

    /** 一次请求的文本结果与用量 */
    private record Answer(String text, Usage usage) {
    }
}
