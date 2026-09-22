package com.agentflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用 MCP（Model Context Protocol）客户端：在 Streamable HTTP 上调 JSON-RPC，
 * 支持 initialize 握手、tools/list 发现、tools/call 调用。
 *
 * <p><b>为什么不一开始就握手</b>：实测不少服务端（含本项目的 SigNoz）tools/call 可无状态直接调用，
 * 强行先握手会平白多一次往返、且一旦服务端不接受握手就把一个本来能用的服务打成不可用。
 * 所以这里的策略是<b>先直接调，失败了才握手重试一次</b>——无状态服务端零开销，
 * 需要会话的服务端也能自愈（握手拿到的 {@code Mcp-Session-Id} 会带上后续请求）。
 * 握手失败会记住「已试过」，避免每次调用都白等一次超时。
 *
 * <p>响应兼容两种形态：纯 JSON 与 SSE（{@code data: {...}} 行）——Streamable HTTP 规范允许
 * 服务端以 text/event-stream 回包，实测也确实有服务端这么做。
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 客户端声明支持的协议版本；服务端若不支持会回它自己的版本，按规范客户端应能接受 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final RestClient restClient;
    private final String url;
    private final Map<String, String> extraHeaders;
    private final AtomicInteger seq = new AtomicInteger(1);
    /** 服务端要求会话时才会被赋值（initialize 的响应头里带） */
    private volatile String sessionId;
    private volatile boolean handshakeAttempted;

    public McpClient(RestClient restClient, String url, Map<String, String> extraHeaders) {
        this.restClient = restClient;
        this.url = url == null ? "" : url.trim();
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    public String url() {
        return url;
    }

    /** 发现工具列表 */
    public List<McpToolInfo> listTools() {
        JsonNode result = rpcResult("tools/list", Map.of());
        List<McpToolInfo> out = new ArrayList<>();
        JsonNode tools = result.path("tools");
        if (tools.isArray()) {
            for (JsonNode node : tools) {
                McpToolInfo info = McpToolInfo.fromJson(node);
                if (info != null) {
                    out.add(info);
                }
            }
        }
        return out;
    }

    /** 调用一个远端工具，返回其文本载荷（MCP 约定 content[0].text） */
    public String callTool(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        JsonNode result = rpcResult("tools/call", params);
        String text = firstContentText(result);
        if (result.path("isError").asBoolean(false)) {
            throw new McpException("MCP 工具 " + name + " 报错：" + truncate(text, 300));
        }
        return text;
    }

    /** 连通性探测：能列出工具即视为可用，返回工具数 */
    public int probe() {
        return listTools().size();
    }

    /* ---------- JSON-RPC 传输 ---------- */

    /**
     * 发一次 JSON-RPC 取 result。首次失败时尝试握手后重试一次——
     * 「失败」既可能是 HTTP 层（服务端要求会话）也可能是 JSON-RPC 层（未知方法/需要初始化）。
     */
    private JsonNode rpcResult(String method, Map<String, Object> params) {
        RuntimeException firstFailure;
        try {
            return extractResult(method, post(method, params, true));
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }
        if (!"initialize".equals(method) && handshake()) {
            try {
                return extractResult(method, post(method, params, true));
            } catch (RuntimeException retryFailure) {
                throw retryFailure;
            }
        }
        throw firstFailure;
    }

    /** 握手：一次 initialize + 一条 initialized 通知；只尝试一次，失败后不再重试 */
    private synchronized boolean handshake() {
        if (handshakeAttempted) {
            return false;
        }
        handshakeAttempted = true;
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.put("capabilities", Map.of());
            params.put("clientInfo", Map.of("name", "AgentFlow", "version", "1.0"));
            extractResult("initialize", post("initialize", params, true));
            // initialized 是通知（无 id、无响应），服务端若不回包也属正常，故失败忽略
            try {
                postNotification("notifications/initialized");
            } catch (Exception ex) {
                log.debug("MCP initialized 通知发送失败（可忽略）：{}", ex.getMessage());
            }
            log.info("MCP 握手成功：{}（会话 {}）", url, sessionId == null ? "无状态" : "已建立");
            return true;
        } catch (Exception ex) {
            log.debug("MCP 握手失败，后续按无状态方式调用：{}（{}）", url, ex.getMessage());
            return false;
        }
    }

    private String post(String method, Map<String, Object> params, boolean withId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (withId) {
            body.put("id", seq.getAndIncrement());
        }
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Streamable HTTP 规范要求客户端同时声明接受 JSON 与 SSE
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        extraHeaders.forEach(headers::set);
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        try {
            String raw = restClient.post()
                    .uri(url)
                    .headers(h -> h.putAll(headers))
                    .body(body)
                    .exchange((request, response) -> {
                        // 用 exchange 而非 retrieve 是为了能读响应头拿 Mcp-Session-Id
                        String sid = response.getHeaders().getFirst("Mcp-Session-Id");
                        if (sid != null && !sid.isBlank()) {
                            sessionId = sid;
                        }
                        String text = new String(response.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (response.getStatusCode().isError()) {
                            throw new McpException("MCP 服务返回 HTTP " + response.getStatusCode().value()
                                    + "：" + truncate(text, 200));
                        }
                        return text;
                    });
            return raw == null ? "" : raw;
        } catch (McpException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new McpException("MCP 服务不可达（" + url + "）：" + ex.getMessage(), ex);
        }
    }

    private void postNotification(String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("params", Map.of());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        extraHeaders.forEach(headers::set);
        if (sessionId != null) {
            headers.set("Mcp-Session-Id", sessionId);
        }
        restClient.post().uri(url).headers(h -> h.putAll(headers)).body(body)
                .retrieve().toBodilessEntity();
    }

    /** 解 JSON-RPC 响应：剥 SSE 外壳、抛 error、取 result */
    private JsonNode extractResult(String method, String raw) {
        if (raw == null || raw.isBlank()) {
            // 通知类请求本就没有响应体；其余情况按服务端未返回处理
            throw new McpException("MCP 服务返回空响应（" + method + "）");
        }
        String json = stripSse(raw);
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new McpException("MCP 响应无法解析（" + method + "）：" + truncate(json, 200), ex);
        }
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            throw new McpException("MCP 调用 " + method + " 失败："
                    + error.path("message").asText(error.toString()));
        }
        JsonNode result = node.get("result");
        if (result == null || result.isNull()) {
            throw new McpException("MCP 响应缺少 result（" + method + "）");
        }
        return result;
    }

    /** 取 result.content[0].text；没有 content 结构时退回整个 result 的 JSON 文本 */
    private static String firstContentText(JsonNode result) {
        JsonNode content = result.get("content");
        if (content != null && content.isArray() && !content.isEmpty()) {
            JsonNode first = content.get(0);
            JsonNode text = first.get("text");
            if (text != null && !text.isNull()) {
                return text.asText();
            }
        }
        return result.toString();
    }

    /**
     * 剥掉 SSE 外壳取最后一条非空 {@code data:} 行。
     *
     * <p>识别方式是「开头不是 {@code {} / [} 就在里面找 data: 行」，而不是「开头是 data: 才当 SSE」：
     * 真实 SSE 回包通常带 {@code event: message} 在前、{@code data: {...}} 在后，
     * 按开头判断会直接把整段原始文本当成 JSON 去解析，然后报「响应无法解析」——
     * 这类故障只在服务端改用 SSE 时才出现，本地自测很难撞上。
     */
    static String stripSse(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        String last = null;
        for (String line : trimmed.split("\n")) {
            String t = line.trim();
            if (t.startsWith("data:")) {
                String payload = t.substring("data:".length()).trim();
                if (!payload.isEmpty()) {
                    last = payload;
                }
            }
        }
        return last == null ? trimmed : last;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** MCP 调用失败（不可达、协议错误、工具自身报错）：调用方转成对用户友好的提示 */
    public static class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }

        public McpException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
