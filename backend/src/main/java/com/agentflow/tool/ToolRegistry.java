package com.agentflow.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(WeatherTool weatherTool, StockTool stockTool, TransitTool transitTool, PoiTool poiTool) {
        tools.put(weatherTool.name(), weatherTool);
        tools.put(stockTool.name(), stockTool);
        tools.put(transitTool.name(), transitTool);
        tools.put(poiTool.name(), poiTool);
    }

    /** 按注册顺序返回工具，供规划 prompt 与文档使用 */
    public List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    /** 生成注入 LLM 规划 prompt 的工具清单 */
    public String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.name()).append("（").append(t.description()).append("）参数 ").append(t.argsHint()).append("\n");
        }
        return sb.toString();
    }

    /** 结构化参数执行；args 为空时由工具自行从指令抽取 */
    public ToolResult execute(String name, Map<String, Object> args, String userCommand) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return null;
        }
        return tool.execute(args == null ? Map.of() : args, userCommand);
    }
}
