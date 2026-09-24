package com.agentflow.mcpserver;

import com.agentflow.engine.Sqlite;
import com.agentflow.engine.StoragePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外 MCP 服务的调用记录（SQLite mcp_server_calls 表）。
 * 端点暴露给外部客户端后，「谁在调、调了什么、成没成」必须可见——不可见的开放端点没法运维。
 */
@Component
public class McpCallStore {

    private static final Logger log = LoggerFactory.getLogger(McpCallStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;
    private final int maxRecords;

    public McpCallStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path,
                        @Value("${agentflow.mcpserver.call-log-max:500}") int maxRecords) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建 MCP 调用记录目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
        this.maxRecords = Math.max(50, maxRecords);
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS mcp_server_calls (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ts TEXT NOT NULL," +
                    "client TEXT DEFAULT ''," +
                    "method TEXT DEFAULT ''," +
                    "tool TEXT DEFAULT ''," +
                    "ok INTEGER NOT NULL DEFAULT 1," +
                    "error TEXT DEFAULT '')");
        } catch (Exception ex) {
            log.error("初始化 MCP 调用记录表失败：{}", ex.getMessage());
        }
    }

    public void record(String client, String method, String tool, boolean ok, String error) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO mcp_server_calls(ts, client, method, tool, ok, error) VALUES(?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, LocalDateTime.now().format(TS));
            ps.setString(2, nz(client));
            ps.setString(3, nz(method));
            ps.setString(4, nz(tool));
            ps.setInt(5, ok ? 1 : 0);
            ps.setString(6, error == null ? "" : (error.length() > 300 ? error.substring(0, 300) : error));
            ps.executeUpdate();
            trim(c);
        } catch (Exception ex) {
            log.warn("写入 MCP 调用记录失败：{}", ex.getMessage());
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM mcp_server_calls ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(200, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", rs.getString("ts"));
                    row.put("client", nz(rs.getString("client")));
                    row.put("method", nz(rs.getString("method")));
                    row.put("tool", nz(rs.getString("tool")));
                    row.put("ok", rs.getInt("ok") == 1);
                    row.put("error", nz(rs.getString("error")));
                    out.add(row);
                }
            }
        } catch (Exception ex) {
            log.warn("读取 MCP 调用记录失败：{}", ex.getMessage());
        }
        return out;
    }

    public void clear() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM mcp_server_calls");
        } catch (Exception ex) {
            log.warn("清空 MCP 调用记录失败：{}", ex.getMessage());
        }
    }

    private void trim(Connection c) {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM mcp_server_calls WHERE id <= " +
                    "(SELECT COALESCE(MAX(id), 0) FROM mcp_server_calls) - " + maxRecords);
        } catch (Exception ignored) {
        }
    }

    private Connection open() throws java.sql.SQLException {
        return Sqlite.open(url);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
