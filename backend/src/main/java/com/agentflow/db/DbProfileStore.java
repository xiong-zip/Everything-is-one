package com.agentflow.db;

import com.agentflow.engine.StoragePaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据库连接配置持久化（SQLite db_profiles 表）：由前端「数据库连接」抽屉管理 */
@Component
public class DbProfileStore {

    private static final Logger log = LoggerFactory.getLogger(DbProfileStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public DbProfileStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS db_profiles (" +
                    "name TEXT PRIMARY KEY," +
                    "config_json TEXT NOT NULL," +
                    "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS db_profiles_meta (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 db_profiles 表失败：{}", ex.getMessage());
        }
    }

    /* ---------- 默认连接（聊天时不点名 profile 即用它） ---------- */

    /** 当前默认连接名；未设置返回 null */
    public String getActive() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM db_profiles_meta WHERE key = 'active'")) {
            try (ResultSet rs = ps.executeQuery()) {
                String name = rs.next() ? rs.getString("value") : null;
                // 默认连接被删后自动失效
                return name != null && find(name) != null ? name : null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    public void setActive(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO db_profiles_meta(key, value) VALUES('active', ?) " +
                             "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, name == null ? "" : name);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("设置默认连接失败：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public boolean save(DbProfile p) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO db_profiles(name, config_json, created_at) VALUES(?, ?, ?) " +
                             "ON CONFLICT(name) DO UPDATE SET config_json=excluded.config_json")) {
            ps.setString(1, p.name());
            ps.setString(2, mapper.writeValueAsString(p));
            ps.setString(3, p.createdAt() == null || p.createdAt().isBlank()
                    ? java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    : p.createdAt());
            ps.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.warn("保存数据库连接失败：{}", ex.getMessage());
            return false;
        }
    }

    public List<DbProfile> list() {
        List<DbProfile> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT config_json FROM db_profiles ORDER BY name")) {
            while (rs.next()) {
                DbProfile p = mapper.readValue(rs.getString("config_json"), DbProfile.class);
                if (p != null && p.name() != null) {
                    out.add(p);
                }
            }
        } catch (Exception ex) {
            log.warn("读取数据库连接失败：{}", ex.getMessage());
        }
        return out;
    }

    public DbProfile find(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT config_json FROM db_profiles WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.readValue(rs.getString("config_json"), DbProfile.class) : null;
            }
        } catch (Exception ex) {
            log.warn("查询数据库连接失败：{}", ex.getMessage());
            return null;
        }
    }

    public boolean delete(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM db_profiles WHERE name = ?")) {
            ps.setString(1, name);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted && name.equals(getActive())) {
                setActive(null);
            }
            return deleted;
        } catch (Exception ex) {
            log.warn("删除数据库连接失败：{}", ex.getMessage());
            return false;
        }
    }

    /** 列表视图：不回传密码 */
    public List<Map<String, Object>> listSafe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DbProfile p : list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("type", p.type());
            m.put("host", p.host());
            m.put("port", p.port());
            m.put("databases", p.databases());
            m.put("username", p.username());
            m.put("schema", p.schemaName());
            m.put("createdAt", p.createdAt());
            out.add(m);
        }
        return out;
    }
}
