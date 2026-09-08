package com.agentflow.model;

import java.util.Map;

/**
 * 计划中的工具调用：name 为注册工具名，args 为结构化参数（由 LLM 规划或启发式抽取产生）。
 */
public record ToolCall(String name, Map<String, Object> args) {
}
