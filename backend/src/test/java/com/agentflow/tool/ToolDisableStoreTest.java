package com.agentflow.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具停用名单：持久化 + 不进规划提示词（停用是为了省每次规划的 token，是核心收益） */
class ToolDisableStoreTest {

    @TempDir
    Path tempDir;

    private ToolDisableStore newStore() {
        ToolDisableStore s = new ToolDisableStore(tempDir.resolve("dis-" + System.nanoTime() + ".db").toString());
        s.init();
        return s;
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " 的说明";
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

    @Test
    void disableStatePersists() {
        ToolDisableStore store = newStore();
        assertFalse(store.isDisabled("gitlab.query"));
        store.setDisabled("gitlab.query", true);
        assertTrue(store.isDisabled("gitlab.query"));
        store.setDisabled("gitlab.query", false);
        assertFalse(store.isDisabled("gitlab.query"));
    }

    /** 重新打开（模拟重启）后停用状态仍在 */
    @Test
    void survivesReload() {
        Path db = tempDir.resolve("persist.db");
        ToolDisableStore first = new ToolDisableStore(db.toString());
        first.init();
        first.setDisabled("kb.query", true);

        ToolDisableStore second = new ToolDisableStore(db.toString());
        second.init();
        assertTrue(second.isDisabled("kb.query"), "重启后停用状态应保留");
        assertEquals(List.of("kb.query"), List.copyOf(second.all()));
    }

    /** 停用只影响提示词清单，执行入口保留（历史计划不该因中途停用而失败） */
    @Test
    void disabledToolLeavesPromptButStaysExecutable() {
        ToolDisableStore store = newStore();
        ToolRegistry registry = new ToolRegistry(List.of(tool("a.tool"), tool("b.tool")), store);
        assertTrue(registry.describeForPrompt().contains("a.tool"));
        assertTrue(registry.describeForPrompt().contains("b.tool"));

        store.setDisabled("a.tool", true);
        registry.refreshPrompt();
        assertFalse(registry.describeForPrompt().contains("a.tool"), "停用后不该出现在规划提示词里");
        assertTrue(registry.describeForPrompt().contains("b.tool"));
        assertTrue(registry.isDisabled("a.tool"));
        // 仍然可执行：规划里没它，但已生成的计划引用它时不至于报「工具不可用」
        assertEquals("ok", registry.execute("a.tool", Map.of(), "").summary());

        store.setDisabled("a.tool", false);
        registry.refreshPrompt();
        assertTrue(registry.describeForPrompt().contains("a.tool"));
    }

    /** 全部停用时清单为空，不能残留换行或空条目 */
    @Test
    void allDisabledYieldsEmptyPrompt() {
        ToolDisableStore store = newStore();
        ToolRegistry registry = new ToolRegistry(List.of(tool("only.tool")), store);
        store.setDisabled("only.tool", true);
        registry.refreshPrompt();
        assertEquals("", registry.describeForPrompt());
    }
}
