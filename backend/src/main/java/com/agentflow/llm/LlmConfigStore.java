package com.agentflow.llm;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM 多档案配置持久化（SQLite）：llm_profiles 存多套模型接入配置，
 * llm_active 记录当前激活的档案；未激活任何档案时回退 .env 默认。
 * 兼容迁移：旧的 llm_config 单行配置自动转为名为「默认」的档案并保持激活。
 */
@Component
public class LlmConfigStore {

    private static final Logger log = LoggerFactory.getLogger(LlmConfigStore.class);

    private final String url;

    public LlmConfigStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS llm_config (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                    "provider TEXT NOT NULL," +
                    "base_url TEXT NOT NULL," +
                    "api_key TEXT NOT NULL," +
                    "model TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS llm_profiles (" +
                    "id TEXT PRIMARY KEY," +
                    "name TEXT NOT NULL," +
                    "provider TEXT NOT NULL," +
                    "base_url TEXT NOT NULL," +
                    "api_key TEXT NOT NULL," +
                    "model TEXT NOT NULL," +
                    "sort_no INTEGER NOT NULL DEFAULT 0," +
                    "created_at TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE IF NOT EXISTS llm_active (" +
                    "id INTEGER PRIMARY KEY CHECK (id = 1)," +
                    "profile_id TEXT NOT NULL)");
            migrateLegacy(c);
        } catch (Exception ex) {
            log.error("初始化 llm_profiles 表失败：{}", ex.getMessage());
        }
    }

    /** 旧版单行 llm_config → 转为「默认」档案并激活（仅一次：档案表为空时） */
    private void migrateLegacy(Connection c) {
        try {
            try (PreparedStatement cnt = c.prepareStatement("SELECT COUNT(*) FROM llm_profiles");
                 ResultSet rs = cnt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            }
            Map<String, String> old;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT provider, base_url, api_key, model FROM llm_config WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                old = new LinkedHashMap<>();
                old.put("provider", rs.getString("provider"));
                old.put("base_url", rs.getString("base_url"));
                old.put("api_key", rs.getString("api_key"));
                old.put("model", rs.getString("model"));
            }
            String id = newId();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO llm_profiles(id, name, provider, base_url, api_key, model, sort_no, created_at) " +
                            "VALUES(?, '默认', ?, ?, ?, ?, 0, ?)")) {
                ps.setString(1, id);
                ps.setString(2, old.get("provider"));
                ps.setString(3, old.get("base_url"));
                ps.setString(4, old.get("api_key"));
                ps.setString(5, old.get("model"));
                ps.setString(6, String.valueOf(System.currentTimeMillis()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO llm_active(id, profile_id) VALUES(1, ?) " +
                            "ON CONFLICT(id) DO UPDATE SET profile_id=excluded.profile_id")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            log.info("已将旧版 LLM 配置迁移为档案「默认」");
        } catch (Exception ex) {
            log.warn("迁移旧版 LLM 配置失败：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 全部档案（按创建顺序），apiKey 原样返回供激活使用，展示层自行掩码 */
    public List<Map<String, String>> listProfiles() {
        List<Map<String, String>> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, name, provider, base_url, api_key, model FROM llm_profiles ORDER BY sort_no, created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("name", rs.getString("name"));
                m.put("provider", rs.getString("provider"));
                m.put("baseUrl", rs.getString("base_url"));
                m.put("apiKey", rs.getString("api_key"));
                m.put("model", rs.getString("model"));
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("读取 LLM 档案失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 新建档案，返回 id */
    public String createProfile(String name, String provider, String baseUrl, String apiKey, String model) {
        String id = newId();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO llm_profiles(id, name, provider, base_url, api_key, model, sort_no, created_at) " +
                             "VALUES(?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(sort_no),0)+1 FROM llm_profiles), ?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, provider);
            ps.setString(4, baseUrl);
            ps.setString(5, apiKey);
            ps.setString(6, model);
            ps.setString(7, String.valueOf(System.currentTimeMillis()));
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("新建 LLM 档案失败：{}", ex.getMessage());
        }
        return id;
    }

    /** 更新档案；apiKey 为 null 表示保持原值。返回是否成功 */
    public boolean updateProfile(String id, String name, String provider, String baseUrl, String apiKey, String model) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE llm_profiles SET name=?, provider=?, base_url=?, " +
                             "api_key=COALESCE(?, api_key), model=? WHERE id=?")) {
            ps.setString(1, name);
            ps.setString(2, provider);
            ps.setString(3, baseUrl);
            ps.setString(4, apiKey);
            ps.setString(5, model);
            ps.setString(6, id);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("更新 LLM 档案失败：{}", ex.getMessage());
            return false;
        }
    }

    public void deleteProfile(String id) {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM llm_profiles WHERE id=?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            // 删除的是激活档案：同时取消激活（回退 .env 默认）
            if (id.equals(activeProfileId())) {
                clearActive();
            }
        } catch (Exception ex) {
            log.warn("删除 LLM 档案失败：{}", ex.getMessage());
        }
    }

    /** 当前激活档案的完整配置；未激活返回 null */
    public Map<String, String> loadActive() {
        String activeId = activeProfileId();
        if (activeId == null) {
            return null;
        }
        for (Map<String, String> p : listProfiles()) {
            if (activeId.equals(p.get("id"))) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", p.get("id"));
                m.put("name", p.get("name"));
                m.put("provider", p.get("provider"));
                m.put("baseUrl", p.get("baseUrl"));
                m.put("apiKey", p.get("apiKey"));
                m.put("model", p.get("model"));
                return m;
            }
        }
        return null;
    }

    /** 激活档案 id；未激活返回 null */
    public String activeProfileId() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT profile_id FROM llm_active WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("profile_id") : null;
        } catch (Exception ex) {
            log.warn("读取激活档案失败：{}", ex.getMessage());
            return null;
        }
    }

    public void activate(String profileId) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO llm_active(id, profile_id) VALUES(1, ?) " +
                             "ON CONFLICT(id) DO UPDATE SET profile_id=excluded.profile_id")) {
            ps.setString(1, profileId);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("激活 LLM 档案失败：{}", ex.getMessage());
        }
    }

    /** 取消激活（回退 .env 默认） */
    public void clearActive() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM llm_active WHERE id = 1");
        } catch (Exception ex) {
            log.warn("取消激活失败：{}", ex.getMessage());
        }
    }
}
