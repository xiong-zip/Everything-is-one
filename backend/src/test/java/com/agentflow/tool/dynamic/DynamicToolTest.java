package com.agentflow.tool.dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 动态工具：URL 拼装（路径参数替换/查询参数/缺参）与配置持久化 */
class DynamicToolTest {

    private DynamicTool tool(String pathTemplate, List<String> pathParams, List<String> queryParams) {
        DynamicToolConfig config = new DynamicToolConfig(
                "openapi.demo", "演示", "{}",
                "http://srv.example", "GET", pathTemplate, pathParams, queryParams);
        return new DynamicTool(new com.agentflow.tool.ToolHttpClient(), config);
    }

    @Test
    void buildUrlReplacesPathParams() {
        DynamicTool t = tool("/api/users/{id}/orders/{orderId}", List.of("id", "orderId"), List.of());
        assertEquals("http://srv.example/api/users/7/orders/42",
                t.buildUrl(java.util.Map.of("id", "7", "orderId", "42")));
    }

    @Test
    void buildUrlAppendsQueryAndEncodes() {
        DynamicTool t = tool("/api/search", List.of(), List.of("kw", "limit"));
        assertEquals("http://srv.example/api/search?kw=%E5%8E%A6%E9%97%A8&limit=5",
                t.buildUrl(java.util.Map.of("kw", "厦门", "limit", "5")));
    }

    @Test
    void buildUrlMissingPathParamIsNull() {
        DynamicTool t = tool("/api/users/{id}", List.of("id"), List.of());
        assertNull(t.buildUrl(java.util.Map.of()));
    }

    @Test
    void toolStoreRoundtrip(@TempDir Path tempDir) {
        ToolStore store = new ToolStore(tempDir.resolve("tools.db").toString());
        store.init();

        DynamicToolConfig config = new DynamicToolConfig(
                "openapi.ping", "回声", "{\"name\": \"名字\"}",
                "http://localhost", "GET", "/ping", List.of(), List.of("name"));
        assertTrue(store.save(config));   // 首次保存 = 新建
        assertFalse(store.save(config));  // 再次保存 = 覆盖

        DynamicToolConfig found = store.find("openapi.ping");
        assertEquals("/ping", found.pathTemplate());
        assertEquals(List.of("name"), found.queryParams());

        assertEquals(1, store.list().size());
        assertTrue(store.delete("openapi.ping"));
        assertNull(store.find("openapi.ping"));
    }
}
