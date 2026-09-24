package com.agentflow.wecom;

import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企微消息发送工具：向指定单聊/群聊发送文本消息。远端副作用操作，强制人工确认。
 */
@Component
public class WecomMessageSendTool extends WecomToolSupport {

    public WecomMessageSendTool(WecomCliRunner cli) {
        super(cli);
    }

    @Override
    public String name() {
        return "wecom.message.send";
    }

    @Override
    public String description() {
        return "发送企业微信消息（执行前必须经用户确认）：向指定会话发送文本（需 chatId 与 text；"
                + "单聊传成员 userid，群聊传群会话 ID）。需部署机 wecom-cli 已扫码授权（工作台 → 企业微信）";
    }

    @Override
    public String argsHint() {
        return "{\"chatId\": \"接收方：单聊为成员 userid，群聊为群会话 ID\", \"text\": \"消息文本\"}";
    }

    @Override
    public boolean requiresConfirm() {
        return true;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        ToolResult g = guard();
        if (g != null) {
            return g;
        }
        String chatId = str(args.get("chatId"));
        if (chatId == null) {
            chatId = str(args.get("chat_id"));
        }
        String text = str(args.get("text"));
        if (text == null) {
            text = str(args.get("content"));
        }
        if (chatId == null || text == null) {
            return ToolResult.note("wecom.message.send 需要 chatId 与 text 参数");
        }
        // 请求体经 stdin（--json -）传入，嵌套 text.content 结构可原样到达
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("msg_type", "text");
        body.put("text", Map.of("content", text));
        return runJson(List.of("message", "send"), body);
    }
}
