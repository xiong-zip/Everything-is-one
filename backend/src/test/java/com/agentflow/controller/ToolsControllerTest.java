package com.agentflow.controller;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolDisableStore;
import com.agentflow.tool.ToolHttpClient;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
import com.agentflow.tool.dynamic.DynamicTool;
import com.agentflow.tool.dynamic.DynamicToolConfig;
import com.agentflow.tool.dynamic.OpenApiImporter;
import com.agentflow.tool.dynamic.ToolStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具管理接口：编辑动态工具（改名/改描述）与停用启用、内置工具保护。
 * 重点锁住改名时不能丢接口配置——baseUrl/路径/参数是导入时生成的，丢了工具就废了。
 */
class ToolsControllerTest {

    @TempDir
    Path tempDir;

    private ToolStore store;
    private ToolRegistry registry;
    private ToolsController controller;
    private ToolDisableStore disableStore;

    private DynamicToolConfig sample(String name) {
        return new DynamicToolConfig(name, "查订单", "{\"id\": \"订单号\"}",
                "http://order.internal", "GET", "/api/orders/{id}",
                List.of("id"), List.of("verbose"));
    }

    private static Tool builtin(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " 内置说明";
            }

            @Override
            public String argsHint() {
                return "{}";
            }

            @Override
            public ToolResult execute(Map<String, Object> args, String userCommand) {
                return ToolResult.note("ok");
            }
        };
    }

    @BeforeEach
    void setUp() {
        String db = tempDir.resolve("tools-" + System.nanoTime() + ".db").toString();
        store = new ToolStore(db);
        store.init();
        disableStore = new ToolDisableStore(db);
        disableStore.init();
        registry = new ToolRegistry(List.of(builtin("k8s.query")), disableStore);
        controller = new ToolsController(registry, disableStore, new OpenApiImporter(new ToolHttpClient()),
                store, new ToolHttpClient());
    }

    @Test
    void editKeepsEndpointConfig() {
        store.save(sample("order.query"));
        registry.register(new DynamicTool(new ToolHttpClient(), sample("order.query")));

        controller.update("order.query", Map.of("name", "order.lookup", "description", "按订单号查订单详情"));

        assertNull(store.find("order.query"), "改名后旧记录应删除");
        DynamicToolConfig updated = store.find("order.lookup");
        assertNotNull(updated);
        assertEquals("按订单号查订单详情", updated.description());
        // 接口配置必须原样保留，否则工具调用会指向错误地址
        assertEquals("http://order.internal", updated.baseUrl());
        assertEquals("/api/orders/{id}", updated.pathTemplate());
        assertEquals(List.of("id"), updated.pathParams());
        assertEquals(List.of("verbose"), updated.queryParams());
        assertEquals("{\"id\": \"订单号\"}", updated.argsHint());

        assertNull(registry.get("order.query"));
        assertNotNull(registry.get("order.lookup"));
    }

    @Test
    void editWithoutNameKeepsNameAndOnlyTouchesDescription() {
        store.save(sample("order.query"));
        registry.register(new DynamicTool(new ToolHttpClient(), sample("order.query")));

        controller.update("order.query", Map.of("description", "只改描述"));

        assertNotNull(store.find("order.query"));
        assertEquals("只改描述", store.find("order.query").description());
    }

    @Test
    void renameToExistingNameRejected() {
        store.save(sample("order.query"));
        store.save(sample("order.other"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.update("order.query", Map.of("name", "order.other")));
        assertTrue(ex.getMessage().contains("已存在"));
        // 两条都还在，改名失败不该留下半删状态
        assertNotNull(store.find("order.query"));
        assertNotNull(store.find("order.other"));
    }

    @Test
    void builtinCannotBeEditedOrDeleted() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.update("k8s.query", Map.of("description", "x")));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> controller.delete("k8s.query"));
        assertTrue(ex.getMessage().contains("停用"), "提示应引导用户改用停用");
    }

    /** 列表把停用的排在后面，并带上 disabled 标记 */
    @Test
    void listMarksAndSortsDisabled() {
        store.save(sample("order.query"));
        registry.register(new DynamicTool(new ToolHttpClient(), sample("order.query")));
        controller.setDisabled("k8s.query", Map.of("disabled", true));

        List<Map<String, Object>> rows = controller.list();
        assertEquals(2, rows.size());
        assertEquals("order.query", rows.get(0).get("name"), "启用的应排在前面");
        assertEquals("k8s.query", rows.get(rows.size() - 1).get("name"));
        assertEquals(Boolean.TRUE, rows.get(rows.size() - 1).get("disabled"));

        controller.setDisabled("k8s.query", Map.of("disabled", false));
        assertEquals(Boolean.FALSE, controller.list().stream()
                .filter(r -> r.get("name").equals("k8s.query")).findFirst().orElseThrow().get("disabled"));
    }

    @Test
    void deleteClearsDynamicToolAndItsDisableFlag() {
        store.save(sample("order.query"));
        registry.register(new DynamicTool(new ToolHttpClient(), sample("order.query")));
        controller.setDisabled("order.query", Map.of("disabled", true));

        controller.delete("order.query");

        assertNull(store.find("order.query"));
        assertNull(registry.get("order.query"));
        assertTrue(disableStore.all().isEmpty(), "删掉的工具不该在停用名单里留下垃圾项");
    }
}
