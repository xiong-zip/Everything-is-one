package com.agentflow.signoz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentflow.tool.ToolHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SigNoz MCP 客户端：在 Streamable HTTP 上调 JSON-RPC 的 tools/call。
 *
 * 实测该服务端不返回 Mcp-Session-Id，tools/call 可无状态直接调用，
 * 因此不做 initialize/会话保持；若将来服务端强制握手，在这里补 initialize 即可。
 */
@Component
public class SigNozMcpClient {

    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final String url;

    public SigNozMcpClient(ToolHttpClient toolHttpClient,
                           @Value("${agentflow.signoz.mcp-url:}") String url) {
        this.restClient = toolHttpClient.restClient();
        this.url = url == null ? "" : url.trim();
    }

    public boolean isConfigured() {
        return !url.isEmpty();
    }

    /** 当前配置的 MCP 地址，供工具描述与错误提示展示 */
    public String url() {
        return url;
    }

    /**
     * 调用一个 MCP 工具，返回其文本载荷（SigNoz 返回的是 JSON 字符串）。
     * 服务不可达、JSON-RPC 报错、工具自身报错都抛异常，由调用方转成对用户友好的提示。
     */
    public String callTool(String name, Map<String, Object> arguments) {
        if (!isConfigured()) {
            throw new IllegalStateException("未配置 SigNoz MCP 地址（agentflow.signoz.mcp-url / SIGNOZ_MCP_URL）");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", seq.getAndIncrement());
        body.put("method", "tools/call");
        body.put("params", params);

        String raw;
        try {
            raw = restClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new IllegalStateException("SigNoz MCP 不可达（" + url + "）：" + ex.getMessage(), ex);
        }
        return extractPayload(name, raw);
    }

    /** 解析 JSON-RPC 响应：兼容 SSE（data: 前缀）与纯 JSON 两种返回形态 */
    private String extractPayload(String toolName, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("SigNoz MCP 返回空响应（" + toolName + "）");
        }
        String json = stripSse(raw);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("SigNoz MCP 响应无法解析（" + toolName + "）：" + truncate(json, 200), ex);
        }
        JsonNode error = node.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException("SigNoz MCP 调用 " + toolName + " 失败："
                    + error.path("message").asText(error.toString()));
        }
        JsonNode result = node.get("result");
        if (result == null || result.isNull()) {
            throw new IllegalStateException("SigNoz MCP 响应缺少 result（" + toolName + "）");
        }
        String text = firstContentText(result);
        if (result.path("isError").asBoolean(false)) {
            throw new IllegalStateException("SigNoz MCP 工具 " + toolName + " 报错：" + truncate(text, 300));
        }
        return text;
    }

    /** 取 result.content[0].text */
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

    /** SSE 形态：多行 data: {...}，取最后一条非空数据行 */
    private static String stripSse(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("data:")) {
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
}
