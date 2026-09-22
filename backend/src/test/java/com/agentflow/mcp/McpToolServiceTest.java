package com.agentflow.mcp;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolHttpClient;
import com.agentflow.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务端存储与注册：这是「填个地址就能接新系统」这条链路里唯一不依赖网络的部分，
 * 也是出错时最难排查的部分（改名丢工具、重名覆盖内置工具都属于「功能突然失灵」型故障）。
 */
class McpToolServiceTest {

    @TempDir
    Path tmp;

    private McpServerStore store;
    private ToolRegistry registry;
    private McpToolService service;

    /** 冒充内置工具，用来验证前缀冲突会被挡住 */
    private static Tool builtin(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "内置";
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

    /** readOnly 用包装类型：null 表示服务端没声明，与「声明为可写」必须能区分 */
    private static McpToolInfo tool(String name, Boolean readOnly) {
        return new McpToolInfo(name, "远端工具 " + name,
                "{\"properties\": {\"q\": {\"type\": \"string\"}}, \"required\": [\"q\"]}",
                List.of("q"), readOnly, "{\"q\": \"string，必填\"}");
    }

    @BeforeEach
    void setUp() {
        store = new McpServerStore(tmp.resolve("mcp.db").toString());
        store.init();
        registry = new ToolRegistry(List.of(builtin("gitlab.query"), builtin("k8s.query")));
        service = new McpToolService(store, registry, new McpClientFactory(new ToolHttpClient()));
    }

    @Test
    void registersCachedToolsOnDemand() {
        service.save("grafana", "http://grafana.local/mcp", Map.of(), false, true, null);
        store.replaceTools("grafana", List.of(tool("query_loki", true)));

        assertEquals(1, service.registerFromCache(store.find("grafana")));
        Tool registered = registry.get("grafana.query_loki");
        assertNotNull(registered);
        assertEquals("grafana.query_loki", registered.name());
        // confirmAll=false 且服务端声明只读 → 不必人工确认
        assertFalse(registered.requiresConfirm());
    }

    @Test
    void confirmPolicyFollowsServerSettingAndReadOnlyHint() {
        service.save("grafana", "http://g/mcp", Map.of(), false, true, null);
        store.replaceTools("grafana", List.of(tool("read_tool", true), tool("write_tool", false), tool("unknown", null)));
        service.registerFromCache(store.find("grafana"));

        assertFalse(registry.get("grafana.read_tool").requiresConfirm());
        assertTrue(registry.get("grafana.write_tool").requiresConfirm());
        // 服务端没声明时：confirmAll=false 表示用户已明确选择放行
        assertFalse(registry.get("grafana.unknown").requiresConfirm());

        // confirmAll=true（默认）则一律要确认，无论服务端怎么说
        service.save("safe", "http://s/mcp", Map.of(), true, true, null);
        store.replaceTools("safe", List.of(tool("read_tool", true)));
        service.registerFromCache(store.find("safe"));
        assertTrue(registry.get("safe.read_tool").requiresConfirm());
    }

    @Test
    void refusesServerNameThatWouldShadowBuiltinTool() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.save("gitlab", "http://g/mcp", Map.of(), false, true, null));
        assertTrue(ex.getMessage().contains("前缀冲突"), ex.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.save("k8s", "http://k/mcp", Map.of(), false, true, null));
        // 换个名字就行
        assertNotNull(service.save("gitlab-mcp", "http://g/mcp", Map.of(), false, true, null));
    }

    @Test
    void rejectsInvalidServerNameAndUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> service.save("Grafana MCP", "http://g/mcp", Map.of(), false, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.save("", "http://g/mcp", Map.of(), false, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.save("grafana", "grafana.local", Map.of(), false, true, null));
        // 大写会被规范化成小写而不是报错
        assertNotNull(service.save("Grafana", "http://g/mcp", Map.of(), false, true, null));
    }

    @Test
    void renameMigratesCachedToolsAndClearsOldRegistration() {
        service.save("grafana", "http://g/mcp", Map.of(), false, true, null);
        store.replaceTools("grafana", List.of(tool("query_loki", true)));
        service.registerFromCache(store.find("grafana"));
        assertNotNull(registry.get("grafana.query_loki"));

        service.save("grafana-new", "http://g/mcp", Map.of(), false, true, "grafana");

        // 旧前缀不能残留（否则改名后工具会出现两份）
        assertNull(registry.get("grafana.query_loki"));
        assertNotNull(registry.get("grafana-new.query_loki"));
        assertNull(store.find("grafana"));
        assertEquals(1, store.toolCount("grafana-new"));
    }

    @Test
    void disablingRemovesToolsAndReenablingRestoresThem() {
        service.save("grafana", "http://g/mcp", Map.of(), false, true, null);
        store.replaceTools("grafana", List.of(tool("query_loki", true)));
        service.registerFromCache(store.find("grafana"));

        service.setEnabled("grafana", false);
        assertNull(registry.get("grafana.query_loki"));
        assertFalse(store.find("grafana").enabled());
        // 关掉不注册，但缓存的清单要留着，重新打开时无需再连服务端
        assertEquals(1, store.toolCount("grafana"));

        service.setEnabled("grafana", true);
        assertNotNull(registry.get("grafana.query_loki"));
    }

    @Test
    void deleteRemovesServerAndItsTools() {
        service.save("grafana", "http://g/mcp", Map.of(), false, true, null);
        store.replaceTools("grafana", List.of(tool("query_loki", true)));
        service.registerFromCache(store.find("grafana"));

        service.delete("grafana");
        assertNull(registry.get("grafana.query_loki"));
        assertNull(store.find("grafana"));
        assertEquals(0, store.toolCount("grafana"));
        assertThrows(IllegalArgumentException.class, () -> service.delete("grafana"));
    }

    @Test
    void overviewReportsToolsAndConfirmPolicy() {
        service.save("grafana", "http://g/mcp", Map.of("Authorization", "Bearer x"), false, true, null);
        store.replaceTools("grafana", List.of(tool("query_loki", true), tool("delete_dash", false)));
        service.registerFromCache(store.find("grafana"));

        List<Map<String, Object>> views = service.overview();
        assertEquals(1, views.size());
        Map<String, Object> view = views.get(0);
        assertEquals("grafana", view.get("name"));
        assertEquals(2, view.get("toolCount"));
        assertEquals(List.of("Authorization"), view.get("headers"));
        assertEquals(Boolean.FALSE, view.get("confirmAll"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) view.get("tools");
        Map<String, Object> first = tools.get(0);
        assertEquals("delete_dash", first.get("name"));
        assertEquals("grafana.delete_dash", first.get("localName"));
        assertTrue((Boolean) first.get("requiresConfirm"));
        assertTrue((Boolean) first.get("registered"));
    }

    @Test
    void headersRoundTripThroughStorage() {
        service.save("auth-svc", "http://a/mcp",
                Map.of("Authorization", "Bearer token-1", "X-Tenant", "team-a"), false, true, null);
        McpServer loaded = store.find("auth-svc");
        assertEquals("Bearer token-1", loaded.headers().get("Authorization"));
        assertEquals("team-a", loaded.headers().get("X-Tenant"));
    }

    @Test
    void omittingHeadersKeepsExistingOnesWhileEmptyMapClearsThem() {
        service.save("auth", "http://a/mcp", Map.of("Authorization", "Bearer t1"), false, true, null);

        // 只改地址时界面不会回显令牌，所以 headers 缺省必须表示「不改」而不是「清空」
        service.save("auth", "http://b/mcp", null, false, true, null);
        assertEquals("Bearer t1", store.find("auth").headers().get("Authorization"));
        assertEquals("http://b/mcp", store.find("auth").url());

        // 改名时同样要跟着走
        service.save("auth-new", "http://b/mcp", null, false, true, "auth");
        assertEquals("Bearer t1", store.find("auth-new").headers().get("Authorization"));

        // 显式传空对象才是真的清空
        service.save("auth-new", "http://b/mcp", Map.of(), false, true, null);
        assertTrue(store.find("auth-new").headers().isEmpty());
    }

    @Test
    void refreshFailureKeepsLastSuccessfulTimeAndRecordsReason() {
        service.save("flaky", "http://f/mcp", Map.of(), false, true, null);

        store.recordRefresh("flaky", "");
        String okAt = store.find("flaky").lastRefreshAt();
        assertFalse(okAt.isBlank());

        store.recordRefresh("flaky", "连接超时");
        McpServer afterFailure = store.find("flaky");
        assertEquals("连接超时", afterFailure.lastError());
        // 关键：「上次连上是 X 时间」必须留着，否则无法区分「三天前连上过」和「从没连上过」
        assertEquals(okAt, afterFailure.lastRefreshAt());

        store.recordRefresh("flaky", "");
        assertEquals("", store.find("flaky").lastError());
    }
}

