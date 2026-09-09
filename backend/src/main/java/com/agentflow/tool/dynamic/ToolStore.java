package com.agentflow.tool.dynamic;

import com.agentflow.engine.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 动态工具持久化（SQLite tools 表，与任务历史同库）：
 * OpenAPI 导入的工具配置存库，重启后由 DynamicToolLoader 重新注册。
 */
@Component
public class ToolStore {

    private static final Logger log = LoggerFactory.getLogger(ToolStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public ToolStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS tools (" +
                    "name TEXT PRIMARY KEY," +
                    "description TEXT DEFAULT ''," +
                    "args_hint TEXT DEFAULT ''," +
                    "config_json TEXT NOT NULL," +
                    "created_at TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 tools 表失败，动态工具将不可用：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** 保存（按 name 覆盖）并返回是否新建 */
    public boolean save(DynamicToolConfig config) {
        boolean exists = find(config.name()) != null;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO tools(name, description, args_hint, config_json, created_at) VALUES(?, ?, ?, ?, ?) " +
                             "ON CONFLICT(name) DO UPDATE SET description=excluded.description, " +
                             "args_hint=excluded.args_hint, config_json=excluded.config_json")) {
            ps.setString(1, config.name());
            ps.setString(2, config.description());
            ps.setString(3, config.argsHint());
            ps.setString(4, mapper.writeValueAsString(config));
            ps.setString(5, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.executeUpdate();
            return !exists;
        } catch (Exception ex) {
            log.warn("保存动态工具失败：{}", ex.getMessage());
            return exists;
        }
    }

    public List<DynamicToolConfig> list() {
        List<DynamicToolConfig> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT config_json FROM tools ORDER BY name")) {
            while (rs.next()) {
                DynamicToolConfig cfg = mapper.readValue(rs.getString("config_json"), DynamicToolConfig.class);
                if (cfg != null && cfg.name() != null) {
                    out.add(cfg);
                }
            }
        } catch (Exception ex) {
            log.warn("读取动态工具失败：{}", ex.getMessage());
        }
        return out;
    }

    public DynamicToolConfig find(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT config_json FROM tools WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.readValue(rs.getString("config_json"), DynamicToolConfig.class) : null;
            }
        } catch (Exception ex) {
            log.warn("查询动态工具失败：{}", ex.getMessage());
            return null;
        }
    }

    public boolean delete(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM tools WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除动态工具失败：{}", ex.getMessage());
            return false;
        }
    }
}
