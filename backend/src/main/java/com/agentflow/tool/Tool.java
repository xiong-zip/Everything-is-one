package com.agentflow.tool;

import java.util.Map;

/**
 * 可被 Agent 调用的工具。args 为 LLM/启发式给出的结构化参数，
 * userCommand 为原始指令全文，工具应优先使用 args、必要时回退到指令抽取。
 */
public interface Tool {

    /** 注册名，如 gitlab.query */
    String name();

    /** 一句话能力描述，用于注入规划 prompt */
    String description();

    /** 参数提示，如 {"city": "城市名"} */
    String argsHint();

    ToolResult execute(Map<String, Object> args, String userCommand);

    /** 写操作/有副作用的工具返回 true：引擎强制进入人工确认流程后才执行 */
    default boolean requiresConfirm() {
        return false;
    }
}
