package com.agentflow.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitLab 地址与令牌都没有内置默认值了（自建 GitLab 地址属内网信息，不再写进公开仓库）。
 * 未配置时必须给出「去 .env 配什么」的明确提示，而不是拿空地址去拼 URL 报一个看不懂的错。
 */
class GitLabToolConfigTest {

    @Test
    void blankBaseUrlPointsToEnv(@TempDir Path dir) {
        ToolResult r = tool("", "", dir).execute(Map.of(), "查我的提交");

        assertTrue(r.summary().contains("GITLAB_URL"), r.summary());
    }

    @Test
    void blankTokenPointsToWorkbenchOrEnv(@TempDir Path dir) {
        ToolResult r = tool("http://gitlab.example.com", "", dir).execute(Map.of(), "查我的提交");

        assertTrue(r.summary().contains("Access Token"), r.summary());
    }

    @Test
    void writeToolAlsoBlocksOnBlankBaseUrl(@TempDir Path dir) {
        GitLabWriteTool tool = new GitLabWriteTool(new ToolHttpClient(), store(dir), "", "");
        ToolResult r = tool.execute(Map.of("op", "comment", "project", "demo"), "给 demo 留个言");

        assertTrue(r.summary().contains("GITLAB_URL"), r.summary());
    }

    private static GitLabTool tool(String baseUrl, String token, Path dir) {
        return new GitLabTool(new ToolHttpClient(), store(dir), baseUrl, token);
    }

    private static GitLabAccountStore store(Path dir) {
        return new GitLabAccountStore(dir.resolve("gitlab-" + System.nanoTime() + ".db").toString());
    }
}
