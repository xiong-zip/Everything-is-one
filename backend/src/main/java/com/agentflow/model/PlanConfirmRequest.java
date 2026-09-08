package com.agentflow.model;

import java.util.List;
import java.util.Map;

/** 计划确认回传：steps 为前端编辑后的完整计划（每项含 kind/tag/title/group/skip/tool） */
public record PlanConfirmRequest(List<Map<String, Object>> steps) {
}
