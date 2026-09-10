package com.agentflow.tool;

/**
 * GitLab Access Token 账户：name 仅作展示标识，身份由 token 决定（/api/v4/user 反查）。
 */
public record GitLabAccount(String name, String token, String createdAt) {
}
