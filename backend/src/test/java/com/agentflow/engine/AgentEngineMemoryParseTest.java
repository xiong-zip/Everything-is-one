package com.agentflow.engine;

import com.agentflow.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记忆指令解析（记住/忘记快速通道）与混合模式的计划受阻判定 */
class AgentEngineMemoryParseTest {

    /* ---------- parseMemoryCommand ---------- */

    @Test
    void recognizesRememberCommand() {
        AgentEngine.MemoryCommand mc = AgentEngine.parseMemoryCommand("记住：日报署名用小熊");
        assertNotNull(mc);
        assertEquals("remember", mc.kind());
        assertEquals("日报署名用小熊", mc.content());

        mc = AgentEngine.parseMemoryCommand("记住, 排查默认用 dev 集群");
        assertNotNull(mc);
        assertEquals("排查默认用 dev 集群", mc.content());

        mc = AgentEngine.parseMemoryCommand("记住我喜欢简洁的汇报风格");
        assertNotNull(mc);
        assertEquals("我喜欢简洁的汇报风格", mc.content());
    }

    @Test
    void recognizesForgetCommand() {
        AgentEngine.MemoryCommand mc = AgentEngine.parseMemoryCommand("忘记:日报署名");
        assertNotNull(mc);
        assertEquals("forget", mc.kind());
        assertEquals("日报署名", mc.content());

        mc = AgentEngine.parseMemoryCommand("删除记忆 dev 集群");
        assertNotNull(mc);
        assertEquals("dev 集群", mc.content());

        mc = AgentEngine.parseMemoryCommand("忘掉 署名");
        assertNotNull(mc);
        assertEquals("署名", mc.content());
    }

    @Test
    void normalCommandsDoNotMatch() {
        // 只有指令开头才算记忆指令，句中提及不算
        assertNull(AgentEngine.parseMemoryCommand("帮我记住这个问题的根因"));
        assertNull(AgentEngine.parseMemoryCommand("分析链路 c4ea16342cf1a0526d22fa20d57c9e2a"));
        assertNull(AgentEngine.parseMemoryCommand("生成周报"));
        assertNull(AgentEngine.parseMemoryCommand("记住"));  // 无内容
        assertNull(AgentEngine.parseMemoryCommand("忘记"));  // 无内容
        assertNull(AgentEngine.parseMemoryCommand(null));
    }

    /* ---------- isStepFailed（混合模式切换判定） ---------- */

    @Test
    void noteTypeResultIsFailure() {
        assertTrue(AgentEngine.isStepFailed(ToolResult.note("未配置 GitLab Token")));
        assertTrue(AgentEngine.isStepFailed(ToolResult.note("工具 gitlab.query 不可用")));
    }

    @Test
    void listResultAndClarifyAreNotFailure() {
        // 正常数据结果不算失败
        assertFalse(AgentEngine.isStepFailed(
                new ToolResult("list", Map.of("k", "v"), List.of("行1"), "摘要")));
        assertFalse(AgentEngine.isStepFailed(new ToolResult("list", null, List.of(), "空列表")));
        // clarify 候选有独立的暂停-点选流程，不算失败
        assertFalse(AgentEngine.isStepFailed(
                ToolResult.withClarify("没有找到，你想查的是：", new ToolResult.Clarify("问题", List.of()))));
        assertFalse(AgentEngine.isStepFailed(null));
    }
}
