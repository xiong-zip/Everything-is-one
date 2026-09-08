package com.agentflow.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：Spring 自动收集所有 Tool Bean（新工具加 @Component 即生效），
 * 并支持运行期注册/注销动态工具（如 OpenAPI 导入生成的工具）。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> springTools) {
        for (Tool t : springTools) {
            register(t);
        }
    }

    /** 运行期注册；重名覆盖（动态工具更新时复用） */
    public synchronized void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public synchronized void unregister(String name) {
        tools.remove(name);
    }

    public synchronized Tool get(String name) {
        return tools.get(name);
    }

    /** 按注册顺序返回工具，供规划 prompt、文档与工具管理页使用 */
    public synchronized List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    /** 生成注入 LLM 规划 prompt 的工具清单 */
    public synchronized String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.name()).append("（").append(t.description()).append("）参数 ").append(t.argsHint()).append("\n");
        }
        return sb.toString();
    }

    /** 结构化参数执行；args 为空时由工具自行从指令抽取 */
    public ToolResult execute(String name, Map<String, Object> args, String userCommand) {
        Tool tool = get(name);
        if (tool == null) {
            return null;
        }
        return tool.execute(args == null ? Map.of() : args, userCommand);
    }
}
