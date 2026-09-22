package com.agentflow.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP 响应形态兼容：Streamable HTTP 允许服务端以 SSE 回包，纯 JSON 与 SSE 都要能解 */
class McpClientTest {

    @Test
    void plainJsonPassesThrough() {
        assertEquals("{\"result\": 1}", McpClient.stripSse("{\"result\": 1}"));
        assertEquals("{\"result\": 1}", McpClient.stripSse("  \n {\"result\": 1} \n"));
    }

    @Test
    void sseTakesLastNonEmptyDataLine() {
        String sse = """
                event: message
                data: {"jsonrpc":"2.0","id":1,"result":{"tools":[]}}

                """;
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}", McpClient.stripSse(sse));
    }

    @Test
    void sseWithPingCommentsAndBlankDataLinesStillResolves() {
        String sse = "data:\n" + ": ping\n" + "data: {\"result\":\"ok\"}\n" + "data:\n";
        assertEquals("{\"result\":\"ok\"}", McpClient.stripSse(sse));
    }

    @Test
    void sseHeaderOnlyFallsBackToRawText() {
        // 只有事件类型行、没有数据行：退回原文，交给上层报「无法解析」而不是自作主张返回空
        String raw = "event: message";
        assertEquals(raw, McpClient.stripSse(raw));
        assertTrue(McpClient.stripSse("data: ").startsWith("data:"));
    }
}
