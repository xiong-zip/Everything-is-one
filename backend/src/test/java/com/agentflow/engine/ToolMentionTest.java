package com.agentflow.engine;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolRegistry;
import com.agentflow.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @工具 直达通道的点名解析：@ + 已注册工具名才算命中，@人名/邮箱/未注册名一律放行给常规规划。
 */
class ToolMentionTest {

    private static final class FakeTool implements Tool {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public String description() {
            return "测试工具";
        }

        public String argsHint() {
            return "{}";
        }

        public ToolResult execute(Map<String, Object> args, String userCommand) {
            return ToolResult.note("ok");
        }
    }

    private final ToolRegistry registry = new ToolRegistry(List.of(
            new FakeTool("wecom.daily"), new FakeTool("gitlab.query"), new FakeTool("k8s.query")));

    @Test
    void extractsRegisteredToolName() {
        assertEquals("wecom.daily",
                AgentEngine.extractToolMention("@wecom.daily 提交今天的工作日报", registry));
        assertEquals("gitlab.query",
                AgentEngine.extractToolMention("先看看 @gitlab.query 查我的提交", registry));
    }

    @Test
    void ignoresUnregisteredOrMalformedMentions() {
        // @ 人名/未注册名/邮箱：不命中，走常规规划
        assertNull(AgentEngine.extractToolMention("@肖雄 帮我写日报", registry));
        assertNull(AgentEngine.extractToolMention("@no.such.tool 干活", registry));
        assertNull(AgentEngine.extractToolMention("邮件 someone@example.com 抄送", registry));
        assertNull(AgentEngine.extractToolMention("没有提及符号", registry));
        assertNull(AgentEngine.extractToolMention(null, registry));
    }

    @Test
    void picksFirstRegisteredAmongMultiple() {
        assertEquals("wecom.daily",
                AgentEngine.extractToolMention("@no.such.tool 之后用 @wecom.daily 提交", registry));
    }
}
