package com.agentflow.tool.dynamic;

import java.util.List;

/**
 * 动态工具的持久化配置：由 OpenAPI 文档导入生成，存 SQLite（tools 表）。
 * pathParams/queryParams 为参数名列表，执行时从 args 取值拼 URL。
 */
public record DynamicToolConfig(
        String name,
        String description,
        String argsHint,
        String baseUrl,
        String method,
        String pathTemplate,
        List<String> pathParams,
        List<String> queryParams) {
}
