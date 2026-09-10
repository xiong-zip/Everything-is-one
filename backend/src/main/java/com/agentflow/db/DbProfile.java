package com.agentflow.db;

/**
 * 数据库连接配置（db-architect）：一个 profile 描述一组主机/账号下的一个或多个库。
 * 存储于 SQLite db_profiles 表，密码明文（内网工具，与原 skill .env 同级安全性）。
 */
public record DbProfile(
        String name,
        String type,        // mysql | oracle | postgresql | dameng
        String host,
        int port,
        String databases,   // 库名，可逗号分隔多个
        String username,
        String password,
        String schemaName,  // Oracle/PG/达梦 的 schema，可空
        String createdAt) {

    public String firstDatabase() {
        if (databases == null || databases.isBlank()) {
            return "";
        }
        return databases.split(",")[0].trim();
    }
}
