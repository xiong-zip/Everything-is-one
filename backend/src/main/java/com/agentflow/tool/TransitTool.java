package com.agentflow.tool;

import com.agentflow.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TransitTool implements Tool {

    private static final String SYSTEM = "你是交通信息助手。根据用户指令中的出发地与目的地，输出一种最合适的交通方式。" +
            "请严格只输出一个 JSON 对象，字段：{\"mode\":\"交通方式\",\"duration\":\"单程时长\",\"price\":\"票价区间\",\"freq\":\"班次信息\"}，" +
            "不要输出任何多余文字、解释或代码块。";

    private final LlmClient llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public TransitTool(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public ToolResult execute(String userCommand) {
        if (!llmClient.isEnabled()) {
            throw new IllegalStateException("未配置 LLM，无法生成交通信息");
        }
        String content = llmClient.chat(SYSTEM, "用户指令：" + userCommand);
        Map<String, Object> data = parseJsonObject(content);
        if (data.isEmpty()) {
            throw new IllegalStateException("LLM 返回格式无法解析");
        }
        String summary = data.get("mode") + " " + data.get("duration") + " " + data.get("price") + " " + data.get("freq");
        return new ToolResult("transit", data, null, summary);
    }

    private Map<String, Object> parseJsonObject(String content) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return r;
            }
            JsonNode node = mapper.readTree(content.substring(start, end + 1));
            if (node.path("mode").asText().isBlank()) {
                return r;
            }
            r.put("mode", node.path("mode").asText());
            r.put("duration", node.path("duration").asText());
            r.put("price", node.path("price").asText());
            r.put("freq", node.path("freq").asText());
        } catch (Exception ignored) {
        }
        return r;
    }
}
