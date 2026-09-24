package com.agentflow.model;

/** 重命名对话：title 为新标题，空白表示清除自定义命名、恢复首条指令自动标题 */
public record RenameSessionRequest(String title) {
}
