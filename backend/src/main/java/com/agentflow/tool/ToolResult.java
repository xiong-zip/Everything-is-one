package com.agentflow.tool;

import java.util.List;
import java.util.Map;

public record ToolResult(String resultType, Map<String, Object> result, List<String> list, String summary) {
}
