package com.agentflow.model;

import java.util.List;
import java.util.Map;

/**
 * command：本轮指令；history：此前轮次的 {command, output}，供引擎理解指代与延续上下文；
 * mode：auto（默认，规划后直接执行）| confirm（计划先推送前端，确认/编辑后再执行）；
 * sessionId：对话归属（多对话模型，空则归入 default）。
 */
public record RunRequest(String command, List<Map<String, String>> history, String mode, String sessionId) {
}
