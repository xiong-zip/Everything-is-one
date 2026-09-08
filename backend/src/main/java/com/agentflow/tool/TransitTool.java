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
    public String name() {
        return "transit.query";
    }

    @Override
    public String description() {
        return "查询两座城市之间的交通方式（LLM 生成，需配置 API Key）";
    }

    @Override
    public String argsHint() {
        return "{\"from\": \"出发城市\", \"to\": \"到达城市\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        if (!llmClient.isEnabled()) {
            return mockResult(args, userCommand);
        }
        try {
            String content = llmClient.chat(SYSTEM, "用户指令：" + userCommand);
            Map<String, Object> data = parseJsonObject(content);
            if (data.isEmpty()) {
                return mockResult(args, userCommand);
            }
            String summary = data.get("mode") + " " + data.get("duration") + " " + data.get("price") + " " + data.get("freq");
            return new ToolResult("transit", data, null, summary);
        } catch (Exception ex) {
            return ToolResult.note("交通信息生成失败：" + ex.getMessage());
        }
    }

    /** 无 LLM 时的降级：由指令派生的参考性模拟数据，明确标注 */
    private ToolResult mockResult(Map<String, Object> args, String userCommand) {
        String from = str(args.get("from"));
        String to = str(args.get("to"));
        String text = (userCommand == null ? "" : userCommand) + " " + from + " " + to;
        String cityFrom = WeatherTool.findCity(text);
        String route = cityFrom != null ? "（" + cityFrom + " 出发）" : "";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", "高铁 / 动车 · 模拟参考");
        data.put("duration", "以 12306 实时查询为准");
        data.put("price", "以 12306 实时查询为准");
        data.put("freq", "每日多班次" + route);
        return new ToolResult("transit", data, null, "模拟参考数据（未配置 API Key，建议以 12306 实时信息为准）");
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

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
