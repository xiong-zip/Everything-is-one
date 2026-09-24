package com.agentflow.mcpserver;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 MCP 服务端的 JSON-RPC 处理器（Streamable HTTP 的无状态形态）。
 * 把 ToolRegistry 里的内置/动态/MCP 工具暴露给外部 MCP 客户端（如企微智能机器人）。
 *
 * <p>安全边界：写操作（requiresConfirm）在外部通道里<b>没有人工放行的机会</b>，
 * 所以一律拒绝并引导到 AgentFlow 界面执行——绝不做无人确认的盲写。
 * readOnlyHint 由 requiresConfirm 取反映射，客户端能据此只挑只读工具。
 */
@Component
public class McpExposeHandler {

    private static final Logger log = LoggerFactory.getLogger(McpExposeHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CONTENT_CHARS = 20_000;
    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final ToolRegistry registry;
    private final McpCallStore calls;
    private final List<String> blacklist;

    public McpExposeHandler(ToolRegistry registry,
                            McpCallStore calls,
                            @Value("${agentflow.mcpserver.blacklist:}") String blacklist) {
        this.registry = registry;
        this.calls = calls;
        this.blacklist = Arrays.stream((blacklist == null ? "" : blacklist).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 处理一条 JSON-RPC 请求。返回响应 JSON；通知类消息（无 id）返回 null——
     * 按 Streamable HTTP 规范对通知不产生响应体。
     */
    public String handle(String body, String client) {
        JsonNode req;
        try {
            req = MAPPER.readTree(body == null ? "" : body);
        } catch (Exception ex) {
            return error(null, -32700, "Parse error");
        }
        if (req == null || !req.isObject() || !req.has("method")) {
            return error(idOf(req), -32600, "Invalid Request");
        }
        String method = req.path("method").asText("");
        JsonNode id = idOf(req);
        if (id == null) {
            return null; // 通知：接受但不回应
        }
        try {
            ObjectNode result = switch (method) {
                case "initialize" -> initialize(req);
                case "ping" -> MAPPER.createObjectNode();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(req.path("params"), client);
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "Method not found: " + method);
            }
            ObjectNode resp = MAPPER.createObjectNode();
            resp.set("jsonrpc", com.fasterxml.jackson.databind.node.TextNode.valueOf("2.0"));
            resp.set("id", id);
            resp.set("result", result);
            return MAPPER.writeValueAsString(resp);
        } catch (IllegalArgumentException ex) {
            calls.record(client, method, "", false, ex.getMessage());
            return error(id, -32602, ex.getMessage());
        } catch (Exception ex) {
            log.warn("MCP 请求处理失败: {}", ex.getMessage());
            calls.record(client, method, "", false, ex.getMessage());
            return error(id, -32603, "Internal error");
        }
    }

    /** 暴露给外部客户端的工具（黑名单过滤后） */
    public List<Tool> exposedTools() {
        return registry.all().stream()
                .filter(t -> blacklist.stream().noneMatch(b -> t.name().startsWith(b)))
                .toList();
    }

    private ObjectNode initialize(JsonNode req) {
        ObjectNode result = MAPPER.createObjectNode();
        // 回显客户端协议版本（认得就用它的，认不得回落到本端实现的版本）
        String clientVersion = req.path("params").path("protocolVersion").asText("");
        result.put("protocolVersion", clientVersion.isBlank() ? PROTOCOL_VERSION : clientVersion);
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "agentflow");
        info.put("version", "1.0");
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        for (Tool t : exposedTools()) {
            ObjectNode node = tools.addObject();
            node.put("name", t.name());
            node.put("description", t.description());
            node.set("inputSchema", schemaFromHint(t.argsHint()));
            ObjectNode annotations = node.putObject("annotations");
            annotations.put("readOnlyHint", !t.requiresConfirm());
        }
        return result;
    }

    private ObjectNode toolsCall(JsonNode params, String client) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("params.name 不能为空");
        }
        Tool tool = registry.get(name);
        if (tool == null || blacklist.stream().anyMatch(b -> name.startsWith(b))) {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        if (tool.requiresConfirm()) {
            result.put("isError", true);
            content.addObject().put("type", "text")
                    .put("text", "「" + name + "」是写操作，外部通道无法人工放行；请到 AgentFlow 界面执行该任务。");
            calls.record(client, "tools/call", name, false, "写操作拒绝");
            return result;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode arguments = params.path("arguments");
        if (arguments.isObject()) {
            arguments.fields().forEachRemaining(e -> args.put(e.getKey(), unwrap(e.getValue())));
        }
        try {
            ToolResult tr = tool.execute(args, "外部 MCP 客户端调用工具 " + name);
            String text = toolResultText(tr);
            content.addObject().put("type", "text").put("text", text);
            boolean hasSummary = tr != null && tr.summary() != null && !tr.summary().isBlank();
            boolean hasPayload = (tr != null && tr.result() != null && !tr.result().isEmpty())
                    || (tr != null && tr.list() != null && !tr.list().isEmpty());
            boolean isErr = !hasSummary && !hasPayload;
            if (isErr) {
                result.put("isError", true);
            }
            calls.record(client, "tools/call", name, !isErr, isErr ? "工具无有效结果" : "");
            return result;
        } catch (Exception ex) {
            result.put("isError", true);
            content.addObject().put("type", "text").put("text", "工具执行失败：" + ex.getMessage());
            calls.record(client, "tools/call", name, false, ex.getMessage());
            return result;
        }
    }

    private static String toolResultText(ToolResult tr) {
        if (tr == null) {
            return "（工具无输出）";
        }
        StringBuilder sb = new StringBuilder();
        if (tr.summary() != null && !tr.summary().isBlank()) {
            sb.append(tr.summary());
        }
        if (tr.result() != null && !tr.result().isEmpty()) {
            try {
                String json = MAPPER.writeValueAsString(tr.result());
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(json);
            } catch (Exception ignored) {
            }
        }
        if (tr.list() != null && !tr.list().isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.join("\n", tr.list()));
        }
        String text = sb.length() == 0 ? "（工具无输出）" : sb.toString();
        return text.length() > MAX_CONTENT_CHARS
                ? text.substring(0, MAX_CONTENT_CHARS) + "\n…（结果过长已截断）"
                : text;
    }

    /** argsHint 是「{key: 说明}」伪 JSON：尽力解析成 object schema，失败则给空 schema */
    private JsonNode schemaFromHint(String hint) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        try {
            JsonNode parsed = MAPPER.readTree(hint == null ? "{}" : hint);
            if (parsed.isObject()) {
                parsed.fields().forEachRemaining(e -> {
                    ObjectNode p = props.putObject(e.getKey());
                    p.put("type", "string");
                    String desc = e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString();
                    if (!desc.isBlank()) {
                        p.put("description", desc);
                    }
                });
            }
        } catch (Exception ignored) {
            // argsHint 不是合法 JSON 时给空 schema，客户端按无参数提示处理
        }
        return schema;
    }

    private static Object unwrap(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        try {
            return MAPPER.convertValue(node, Object.class);
        } catch (Exception ex) {
            return node.asText("");
        }
    }

    private static JsonNode idOf(JsonNode req) {
        if (req == null || !req.has("id")) {
            return null;
        }
        return req.get("id");
    }

    private static String error(JsonNode id, int code, String message) {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) {
            resp.set("id", id);
        }
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return resp.toString();
    }
}
