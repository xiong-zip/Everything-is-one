package com.agentflow.mcp;

import com.agentflow.engine.Sqlite;
import com.agentflow.engine.StoragePaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务端与远端工具清单的持久化（SQLite mcp_servers / mcp_tools 表，与任务历史同库）。
 *
 * <p>工具清单<b>落库缓存</b>是这个存储存在的主要理由：启动时若逐个连服务端拉 tools/list，
 * 任何一个不可达都会拖慢启动，而且离线时工具会整体消失、动态规划质量跟着掉。
 * 所以启动只读缓存（零网络），需要更新时由用户点「刷新工具」显式触发。
 *
 * <p>{@code required_args} 用逗号连接存、{@code read_only} 用 -1/0/1 三态存：
 * 都是为了让「服务端没给 readOnlyHint」这个信息不丢——它和「明确说不是只读」是两回事。
 */
@Component
public class McpServerStore {

    private static final Logger log = LoggerFactory.getLogger(McpServerStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {
    };

    private final String url;

    public McpServerStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建 MCP 存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
    }

    /** 落库的远端工具（够重建一个可调用的 Tool，不必再连服务端） */
    public record StoredTool(String server, String name, String description, String inputSchema,
                            List<String> requiredArgs, Boolean readOnlyHint) {
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS mcp_servers (" +
                    "name TEXT PRIMARY KEY," +
                    "url TEXT NOT NULL," +
                    "headers_json TEXT DEFAULT '{}'," +
                    "confirm_all INTEGER NOT NULL DEFAULT 1," +
                    "enabled INTEGER NOT NULL DEFAULT 1," +
                    "created_at TEXT NOT NULL," +
                    "last_refresh_at TEXT DEFAULT ''," +
                    "last_error TEXT DEFAULT '')");
            st.execute("CREATE TABLE IF NOT EXISTS mcp_tools (" +
                    "server TEXT NOT NULL," +
                    "name TEXT NOT NULL," +
                    "description TEXT DEFAULT ''," +
                    "input_schema TEXT DEFAULT '{}'," +
                    "required_args TEXT DEFAULT ''," +
                    "read_only INTEGER DEFAULT -1," +
                    "PRIMARY KEY(server, name))");
        } catch (Exception ex) {
            log.error("初始化 MCP 表失败，MCP 工具将不可用：{}", ex.getMessage());
        }
    }

    /* ---------- 服务端配置 ---------- */

    public List<McpServer> servers() {
        List<McpServer> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM mcp_servers ORDER BY name")) {
            while (rs.next()) {
                out.add(mapServer(rs));
            }
        } catch (Exception ex) {
            log.warn("读取 MCP 服务端失败：{}", ex.getMessage());
        }
        return out;
    }

    public McpServer find(String name) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM mcp_servers WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapServer(rs) : null;
            }
        } catch (Exception ex) {
            log.warn("查询 MCP 服务端失败：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 保存服务端（按 name 覆盖）。{@code originalName} 非空且与新 name 不同时视为改名：
     * 连同它名下的工具一起搬过去，否则改个名字工具就全丢了。
     */
    public void save(McpServer server, String originalName) {
        String now = LocalDateTime.now().format(TS);
        Connection c = null;
        try {
            c = open();
            // 改名场景要同时搬工具行、删旧行、写新行，必须原子：中断会留下「旧服务名下挂着新工具」的错位
            c.setAutoCommit(false);
            boolean exists = existsByName(c, server.name());
            String createdAt = now;
            if (exists) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT created_at FROM mcp_servers WHERE name = ?")) {
                    ps.setString(1, server.name());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            createdAt = rs.getString(1);
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mcp_servers(name, url, headers_json, confirm_all, enabled, created_at," +
                            " last_refresh_at, last_error) VALUES(?, ?, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(name) DO UPDATE SET url=excluded.url, headers_json=excluded.headers_json," +
                            " confirm_all=excluded.confirm_all, enabled=excluded.enabled")) {
                ps.setString(1, server.name());
                ps.setString(2, server.url());
                ps.setString(3, MAPPER.writeValueAsString(server.headers() == null ? Map.of() : server.headers()));
                ps.setInt(4, server.confirmAll() ? 1 : 0);
                ps.setInt(5, server.enabled() ? 1 : 0);
                ps.setString(6, server.createdAt() == null || server.createdAt().isBlank() ? createdAt : server.createdAt());
                ps.setString(7, nz(server.lastRefreshAt()));
                ps.setString(8, nz(server.lastError()));
                ps.executeUpdate();
            }
            if (originalName != null && !originalName.isBlank() && !originalName.equals(server.name())) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE mcp_tools SET server = ? WHERE server = ?")) {
                    ps.setString(1, server.name());
                    ps.setString(2, originalName);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_servers WHERE name = ?")) {
                    ps.setString(1, originalName);
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (Exception ex) {
            Sqlite.rollbackQuietly(c);
            log.warn("保存 MCP 服务端失败：{}", ex.getMessage());
        } finally {
            Sqlite.closeQuietly(c);
        }
    }

    /** 只更新开关（不碰地址与已缓存的工具清单） */
    public boolean setEnabled(String name, boolean enabled) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE mcp_servers SET enabled = ? WHERE name = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("更新 MCP 开关失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 记录一次刷新结果。成功则清空失败原因并刷新时间戳；
     * 失败只写原因、<b>保留上一次成功的时间</b>——「上次连上是三天前」和「从来没连上过」
     * 是完全不同的判断依据，把时间抹掉就看不出来了。
     */
    public void recordRefresh(String name, String error) {
        boolean ok = error == null || error.isBlank();
        String sql = ok
                ? "UPDATE mcp_servers SET last_refresh_at = ?, last_error = '' WHERE name = ?"
                : "UPDATE mcp_servers SET last_error = ? WHERE name = ?";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (ok) {
                ps.setString(1, LocalDateTime.now().format(TS));
                ps.setString(2, name);
            } else {
                ps.setString(1, truncate(error, 300));
                ps.setString(2, name);
            }
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("更新 MCP 刷新状态失败：{}", ex.getMessage());
        }
    }

    /** 删除服务端连同它缓存的工具清单 */
    public boolean delete(String name) {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_tools WHERE server = ?")) {
                ps.setString(1, name);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_servers WHERE name = ?")) {
                ps.setString(1, name);
                return ps.executeUpdate() > 0;
            }
        } catch (Exception ex) {
            log.warn("删除 MCP 服务端失败：{}", ex.getMessage());
            return false;
        }
    }

    /* ---------- 工具清单缓存 ---------- */

    /** 全量替换某个服务端的工具清单（刷新时调用） */
    public void replaceTools(String server, List<McpToolInfo> tools) {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM mcp_tools WHERE server = ?")) {
                ps.setString(1, server);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mcp_tools(server, name, description, input_schema, required_args, read_only)" +
                            " VALUES(?, ?, ?, ?, ?, ?)")) {
                for (McpToolInfo t : tools) {
                    ps.setString(1, server);
                    ps.setString(2, t.name());
                    ps.setString(3, nz(t.description()));
                    ps.setString(4, nz(t.inputSchema()));
                    ps.setString(5, String.join(",", t.requiredArgs()));
                    ps.setInt(6, t.readOnlyHint() == null ? -1 : (t.readOnlyHint() ? 1 : 0));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (Exception ex) {
            log.warn("保存 MCP 工具清单失败：{}", ex.getMessage());
        }
    }

    public List<StoredTool> tools(String server) {
        List<StoredTool> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM mcp_tools WHERE server = ? ORDER BY name")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int ro = rs.getInt("read_only");
                    out.add(new StoredTool(rs.getString("server"), rs.getString("name"),
                            nz(rs.getString("description")), nz(rs.getString("input_schema")),
                            split(rs.getString("required_args")), ro < 0 ? null : ro == 1));
                }
            }
        } catch (Exception ex) {
            log.warn("读取 MCP 工具清单失败：{}", ex.getMessage());
        }
        return out;
    }

    public int toolCount(String server) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM mcp_tools WHERE server = ?")) {
            ps.setString(1, server);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception ex) {
            return 0;
        }
    }

    private static boolean existsByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM mcp_servers WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static McpServer mapServer(ResultSet rs) throws SQLException {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            Map<String, String> parsed = MAPPER.readValue(nz(rs.getString("headers_json")), HEADERS_TYPE);
            if (parsed != null) {
                headers.putAll(parsed);
            }
        } catch (Exception ignored) {
            // 头配置损坏不影响服务端本身可用（只是可能鉴权失败），不必让整行读不出来
        }
        return new McpServer(rs.getString("name"), nz(rs.getString("url")), headers,
                rs.getInt("confirm_all") == 1, rs.getInt("enabled") == 1,
                nz(rs.getString("created_at")), nz(rs.getString("last_refresh_at")),
                nz(rs.getString("last_error")));
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? nz(s) : s.substring(0, max) + "…";
    }
}
