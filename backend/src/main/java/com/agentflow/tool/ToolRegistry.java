package com.agentflow.tool;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRegistry(WeatherTool weatherTool, StockTool stockTool, TransitTool transitTool, PoiTool poiTool) {
        tools.put("weather.query", weatherTool);
        tools.put("stock.query", stockTool);
        tools.put("transit.query", transitTool);
        tools.put("poi.recommend", poiTool);
    }

    public ToolResult execute(String name, String userCommand) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return null;
        }
        return tool.execute(userCommand);
    }
}
