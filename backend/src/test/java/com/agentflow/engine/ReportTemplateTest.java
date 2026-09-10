package com.agentflow.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 报告模板的提交清洗与单日整理：Merge 噪音过滤、前缀剥离、分条限额 */
class ReportTemplateTest {

    @Test
    void cleanTitleFiltersMergeNoise() {
        assertNull(AgentEngine.cleanCommitTitle("Merge branch 'dev-task-xx' into 'master'"));
        assertNull(AgentEngine.cleanCommitTitle("Merge branch 'xiaoxiong' into 'master'"));
        assertNull(AgentEngine.cleanCommitTitle("revert \"Merge branch 'x'\""));
        assertNull(AgentEngine.cleanCommitTitle(""));
    }

    @Test
    void cleanTitleStripsConventionalPrefix() {
        assertEquals("待办模块：新增免权限过滤的菜单树查询接口",
                AgentEngine.cleanCommitTitle("feat(todo): 新增免权限过滤的菜单树查询接口"));
        assertEquals("修正MenuTreeController类声明多余空格",
                AgentEngine.cleanCommitTitle("style: 修正MenuTreeController类声明多余空格"));
        assertEquals("普通提交标题", AgentEngine.cleanCommitTitle("普通提交标题"));
    }

    @Test
    void dayItemsSplitsAndDedupes() {
        List<String> items = AgentEngine.dayItems(List.of(
                "Merge branch 'a' into 'master'",
                "feat(todo): 新增菜单树查询接口",
                "docs(todo): 配置更名",
                "fix(todo): 修复空指针",
                "feat(todo): 统计接口",
                "feat(todo): 新增菜单树查询接口"));
        assertEquals(List.of(
                "待办模块：新增菜单树查询接口",
                "待办模块：配置更名",
                "待办模块：修复空指针",
                "待办模块：统计接口"), items);
    }

    @Test
    void dayItemsCapsAtMax() {
        List<String> raw = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            raw.add("feat(core): 事项" + i);
        }
        assertEquals(6, AgentEngine.dayItems(raw).size());
    }

    @Test
    void dayItemsAllNoiseReturnsEmpty() {
        assertTrue(AgentEngine.dayItems(List.of(
                "Merge branch 'a' into 'master'", "Merge branch 'b' into 'master'")).isEmpty());
    }
}
