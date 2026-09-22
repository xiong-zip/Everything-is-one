package com.agentflow.mcp;

import com.agentflow.tool.ToolHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP 工具定义解析：tools/list 节点到可注入 prompt 的参数提示 */
class McpToolInfoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static McpToolInfo parse(String json) throws Exception {
        return McpToolInfo.fromJson(MAPPER.readTree(json));
    }

    @Test
    void parsesNameDescriptionAndSchema() throws Exception {
        McpToolInfo info = parse("""
                {"name": "query_loki",
                 "description": "查询 Loki 日志",
                 "inputSchema": {"type": "object",
                                 "properties": {"query": {"type": "string", "description": "LogQL 语句"},
                                                "limit": {"type": "integer"}},
                                 "required": ["query"]}}
                """);
        assertNotNull(info);
        assertEquals("query_loki", info.name());
        assertEquals("查询 Loki 日志", info.description());
        assertEquals(List.of("query"), info.requiredArgs());
        assertTrue(info.argsHint().contains("\"query\": \"string LogQL 语句，必填\""), info.argsHint());
        assertTrue(info.argsHint().contains("\"limit\": \"integer，可选\""), info.argsHint());
    }

    @Test
    void missingNameIsDroppedBecauseItCannotBeCalled() throws Exception {
        assertNull(parse("{\"description\": \"没有名字\"}"));
        assertNull(McpToolInfo.fromJson(null));
        assertNull(parse("{\"name\": \"  \"}"));
    }

    @Test
    void readOnlyHintIsTriStateNotBoolean() throws Exception {
        // 服务端没说（null）与明确说不是只读（false）必须能区分，否则「未知工具是否要人工确认」没法决策
        assertNull(parse("{\"name\": \"a\"}").readOnlyHint());
        assertEquals(Boolean.TRUE, parse("{\"name\": \"a\", \"annotations\": {\"readOnlyHint\": true}}").readOnlyHint());
        assertEquals(Boolean.FALSE, parse("{\"name\": \"a\", \"annotations\": {\"readOnlyHint\": false}}").readOnlyHint());
    }

    @Test
    void toleratesMissingOrOddSchema() throws Exception {
        McpToolInfo noSchema = parse("{\"name\": \"a\"}");
        assertEquals("{}", noSchema.argsHint());
        assertTrue(noSchema.requiredArgs().isEmpty());

        // 无 type 的嵌套对象参数退化成 object，不抛异常
        McpToolInfo nested = parse("""
                {"name": "b", "inputSchema": {"properties": {"filter": {"properties": {"a": {"type": "string"}}}}}}
                """);
        assertTrue(nested.argsHint().contains("\"filter\": \"object，可选\""), nested.argsHint());
    }

    @Test
    void rerendersHintFromStoredSchemaOnRestart() {
        // 重启后只有库里存的 schema 字符串，必须能还原出同样的提示
        String hint = McpToolInfo.renderArgsHintFromSchema(
                "{\"properties\": {\"query\": {\"type\": \"string\", \"description\": \"语句\"}}}", List.of("query"));
        assertTrue(hint.contains("\"query\": \"string 语句，必填\""), hint);
        // schema 损坏时退化成空提示，不能让整个工具注册失败
        assertEquals("{}", McpToolInfo.renderArgsHintFromSchema("不是 JSON", List.of("x")));
    }

    @Test
    void argsHintIsBoundedSoItCannotFloodThePlanningPrompt() throws Exception {
        // 远端 schema 可以极长：实测 SigNoz MCP 单个工具的参数说明就近 4KB。
        // 这些文本会整体注入每次规划 prompt，必须封顶。
        StringBuilder props = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            if (i > 0) {
                props.append(',');
            }
            props.append("\"p").append(i).append("\": {\"type\": \"string\", \"description\": \"")
                    .append("很长的参数说明".repeat(30)).append("\"}");
        }
        McpToolInfo info = parse("{\"name\": \"big\", \"inputSchema\": {\"properties\": {"
                + props + "}, \"required\": [\"p0\"]}}");

        String hint = info.argsHint();
        assertTrue(hint.length() <= 625, "参数提示长度 " + hint.length());
        // 「还有多少个参数没列出来」必须存活，否则模型会以为工具只有列出来那几个参数
        assertTrue(hint.contains("共 30 个参数"), hint);
        // 前面的参数仍要保留（必填参数通常在最前），不能一截了之
        assertTrue(hint.contains("\"p0\""), hint);
        assertTrue(hint.contains("必填"), hint);
    }

    @Test
    void toolDescriptionIsBoundedToo() {
        McpServer server = new McpServer("s", "http://x/mcp", Map.of(), false, true, null, null, null);
        McpServerStore.StoredTool stored = new McpServerStore.StoredTool("s", "tool",
                "远端返回的一大段用法文档。".repeat(50), "{}", List.of(), null);
        McpTool tool = new McpTool(server, stored, new McpClientFactory(new ToolHttpClient()));
        assertTrue(tool.description().length() < 260, "描述长度 " + tool.description().length());
        assertTrue(tool.description().contains("来自 MCP 服务 s"), tool.description());
    }

    @Test
    void localToolNameIsSanitizedButRemoteNameIsKept() {
        assertEquals("grafana.query_loki", McpTool.localName("grafana", "query_loki"));
        // 远端工具名里的点号等字符会破坏「前缀.名字」的结构，注册时替换掉
        assertEquals("grafana.a_b_c", McpTool.localName("grafana", "a.b/c"));
        // 清洗后只剩一个分隔点：工具名的第一段必须稳定等于服务端名（冲突检测依赖这一点）
        assertEquals(1, McpTool.localName("grafana", "a.b/c").length()
                - McpTool.localName("grafana", "a.b/c").replace(".", "").length());
    }
}
