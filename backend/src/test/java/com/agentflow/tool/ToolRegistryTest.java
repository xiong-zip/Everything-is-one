package com.agentflow.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具注册表：自动收集语义（构造注入 List）与运行期注册/注销/分发 */
class ToolRegistryTest {

    /** 极简测试工具 */
    private static class EchoTool implements Tool {
        @Override
        public String name() {
            return "test.echo";
        }

        @Override
        public String description() {
            return "回声工具";
        }

        @Override
        public String argsHint() {
            return "{\"msg\": \"消息\"}";
        }

        @Override
        public ToolResult execute(Map<String, Object> args, String userCommand) {
            return ToolResult.note("echo:" + args.getOrDefault("msg", ""));
        }
    }

    @Test
    void registerAndExecute() {
        ToolRegistry registry = new ToolRegistry(java.util.List.of(new EchoTool()));
        assertEquals(1, registry.all().size());
        ToolResult result = registry.execute("test.echo", Map.of("msg", "hi"), "任意指令");
        assertEquals("echo:hi", result.summary());
        assertTrue(registry.describeForPrompt().contains("test.echo"));
    }

    @Test
    void dynamicRegisterAndUnregister() {
        ToolRegistry registry = new ToolRegistry(java.util.List.of());
        registry.register(new EchoTool());
        assertEquals("test.echo", registry.get("test.echo").name());

        registry.unregister("test.echo");
        assertNull(registry.get("test.echo"));
        assertNull(registry.execute("test.echo", Map.of(), ""));
        assertFalse(registry.all().stream().anyMatch(t -> t.name().equals("test.echo")));
    }

    @Test
    void unknownToolReturnsNull() {
        ToolRegistry registry = new ToolRegistry(java.util.List.of());
        assertNull(registry.execute("nope", Map.of(), ""));
    }
}
