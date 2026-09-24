package com.agentflow.mcpserver;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对外 MCP 服务端的 JSON-RPC 协议测试：用假工具构造注册表，不依赖 Spring 上下文。
 */
class McpExposeHandlerTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** 只读假工具：记录收到的参数并返回固定结果 */
    static class EchoTool implements Tool {
        Map<String, Object> lastArgs;

        public String name() {
            return "fake.echo";
        }

        public String description() {
            return "回声工具（测试）";
        }

        public String argsHint() {
            return "{\"q\": \"查询词\"}";
        }

        public ToolResult execute(Map<String, Object> args, String userCommand) {
            this.lastArgs = args;
            return ToolResult.note("echo:" + args.getOrDefault("q", ""));
        }
    }

    static class DangerousTool implements Tool {
        public String name() {
            return "fake.write";
        }

        public String description() {
            return "写操作工具（测试）";
        }

        public String argsHint() {
            return "{}";
        }

        public boolean requiresConfirm() {
            return true;
        }

        public ToolResult execute(Map<String, Object> args, String userCommand) {
            return ToolResult.note("不应该被执行到这里");
        }
    }

    private final ToolRegistry registry = new ToolRegistry(List.of(new EchoTool(), new DangerousTool()));

    private McpExposeHandler handler(@TempDir Path dir) {
        McpCallStore store = new McpCallStore(dir.resolve("test.db").toString(), 100);
        store.init();
        return new McpExposeHandler(registry, store, "");
    }

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void initializeEchoesClientProtocolVersion(@TempDir Path dir) throws Exception {
        String resp = handler(dir).handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2025-06-18","capabilities":{}}}""", "test");
        JsonNode node = json(resp);
        assertEquals("2025-06-18", node.path("result").path("protocolVersion").asText());
        assertTrue(node.path("result").path("capabilities").has("tools"));
    }

    @Test
    void toolsListDerivesReadOnlyHintAndSchema(@TempDir Path dir) throws Exception {
        String resp = handler(dir).handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", "test");
        JsonNode tools = json(resp).path("result").path("tools");
        assertEquals(2, tools.size());
        JsonNode echo = tools.get(0);
        assertEquals("fake.echo", echo.path("name").asText());
        assertTrue(echo.path("annotations").path("readOnlyHint").asBoolean());
        // argsHint 的说明应转成 schema 里的字段描述
        assertEquals("查询词", echo.path("inputSchema").path("properties").path("q").path("description").asText());
        assertFalse(tools.get(1).path("annotations").path("readOnlyHint").asBoolean());
    }

    @Test
    void toolsCallExecutesReadOnlyTool(@TempDir Path dir) throws Exception {
        EchoTool echo = (EchoTool) registry.get("fake.echo");
        String resp = handler(dir).handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"fake.echo","arguments":{"q":"hello"}}}""", "test");
        JsonNode result = json(resp).path("result");
        String text = result.path("content").get(0).path("text").asText();
        assertTrue(text.startsWith("echo:hello"));
        assertTrue(text.contains("\"note\":\"echo:hello\"")); // 摘要 + 结果 JSON 都带回
        assertFalse(result.path("isError").asBoolean(false));
        assertEquals("hello", echo.lastArgs.get("q"));
    }

    @Test
    void toolsCallRejectsWriteTool(@TempDir Path dir) throws Exception {
        String resp = handler(dir).handle("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"fake.write","arguments":{}}}""", "test");
        JsonNode result = json(resp).path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText().contains("写操作"));
    }

    @Test
    void unknownMethodAndToolError(@TempDir Path dir) throws Exception {
        JsonNode err = json(handler(dir).handle(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}", "test")).path("error");
        assertEquals(-32601, err.path("code").asInt());

        JsonNode unknownTool = json(handler(dir).handle("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call",
                 "params":{"name":"no.such.tool","arguments":{}}}""", "test")).path("error");
        assertEquals(-32602, unknownTool.path("code").asInt());
    }

    @Test
    void notificationsProduceNoResponse(@TempDir Path dir) {
        assertNull(handler(dir).handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "test"));
    }

    @Test
    void parseErrorReported() throws Exception {
        McpCallStore store = new McpCallStore("/tmp/af-mcp-test/none.db", 100);
        McpExposeHandler h = new McpExposeHandler(registry, store, "");
        assertNotNull(h.handle("not json", "test"));
    }
}
