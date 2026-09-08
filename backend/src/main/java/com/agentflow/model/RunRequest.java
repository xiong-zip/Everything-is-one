package com.agentflow.model;

import java.util.List;
import java.util.Map;

/** command：本轮指令；history：此前轮次的 {command, output}，供引擎理解指代与延续上下文 */
public record RunRequest(String command, List<Map<String, String>> history) {
}
