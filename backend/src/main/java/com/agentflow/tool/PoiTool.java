package com.agentflow.tool;

import com.agentflow.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PoiTool implements Tool {

    private static final String SYSTEM = "你是本地美食推荐助手。根据用户指令中的城市，推荐当地有代表性的特色美食。" +
            "请严格只输出一个 JSON 字符串数组，每个元素是一道美食名称（可附带名店），共 5 个，" +
            "不要输出任何多余文字、解释或代码块。";

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public PoiTool(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public ToolResult execute(String userCommand) {
        if (!llmClient.isEnabled()) {
            throw new IllegalStateException("未配置 LLM，无法生成美食推荐");
        }
        String content = llmClient.chat(SYSTEM, "用户指令：" + userCommand);
        List<String> list = parseJsonArray(content);
        if (list.isEmpty()) {
            throw new IllegalStateException("LLM 返回格式无法解析");
        }
        String summary = "推荐美食：" + String.join("、", list);
        return new ToolResult("list", null, list, summary);
    }

    private List<String> parseJsonArray(String content) {
        List<String> r = new ArrayList<>();
        try {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return r;
            }
            JsonNode node = mapper.readTree(content.substring(start, end + 1));
            if (node.isArray()) {
                node.forEach(n -> r.add(n.asText()));
            }
        } catch (Exception ignored) {
        }
        return r;
    }
}
