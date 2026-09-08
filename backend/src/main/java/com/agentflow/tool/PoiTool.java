package com.agentflow.tool;

import com.agentflow.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public String name() {
        return "poi.recommend";
    }

    @Override
    public String description() {
        return "推荐当地特色美食/景点（LLM 生成，需配置 API Key）";
    }

    @Override
    public String argsHint() {
        return "{\"city\": \"城市名\", \"keyword\": \"美食或景点关键词\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!llmClient.isEnabled()) {
            return mockResult(args, userCommand);
        }
        try {
            String content = llmClient.chat(SYSTEM, "用户指令：" + userCommand);
            List<String> list = parseJsonArray(content);
            if (list.isEmpty()) {
                return mockResult(args, userCommand);
            }
            String summary = "推荐美食：" + String.join("、", list);
            return new ToolResult("list", null, list, summary);
        } catch (Exception ex) {
            return ToolResult.note("推荐生成失败：" + ex.getMessage());
        }
    }

    /** 无 LLM 时的降级：给出探索方向而非虚构具体店铺，明确标注模拟 */
    private ToolResult mockResult(Map<String, Object> args, String userCommand) {
        String text = (userCommand == null ? "" : userCommand) + " " + args.getOrDefault("city", "");
        String city = WeatherTool.findCity(text);
        String prefix = city == null ? "目的地" : city;
        List<String> list = List.of(
                prefix + " 老字号招牌菜 · 探店方向（模拟）",
                prefix + " 本地人气小吃街（模拟）",
                prefix + " 必尝特色早点（模拟）",
                prefix + " 时令风味菜（模拟）",
                prefix + " 特色伴手礼（模拟）");
        return new ToolResult("list", null, list, "模拟推荐方向（未配置 API Key，接入后可生成具体美食清单）");
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
