package com.agentflow.tool;

import java.util.List;

/**
 * GitLab Access Token 账户：name 仅作展示标识，身份由 token 决定（/api/v4/user 反查）。
 *
 * <p>authors 是「提交作者别名」：本机 git 配置的作者名/邮箱常与 GitLab 档案不一致
 * （如档案是姓名+公司邮箱，git 却配置了账号名+个人邮箱），仓库提交里查得到、档案里查不到，
 * 日报/周报按档案身份过滤时这些提交会被整批漏掉——别名列表里的每一项会同时与提交的
 * author_name 和 author_email 匹配（不分大小写）。
 */
public record GitLabAccount(String name, String token, String createdAt, List<String> authors) {

    public GitLabAccount {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    /** 兼容旧调用（无别名） */
    public GitLabAccount(String name, String token, String createdAt) {
        this(name, token, createdAt, List.of());
    }
}
