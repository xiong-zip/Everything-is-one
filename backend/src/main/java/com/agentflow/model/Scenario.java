package com.agentflow.model;

import java.util.List;

public record Scenario(String id, String command, String shortName, List<AgentStep> steps, FinalData finalData) {
}
