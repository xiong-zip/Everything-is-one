package com.agentflow.tool;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab Access Token 账户持久化（SQLite gitlab_accounts 表）：由前端「GitLab 账户」抽屉管理。
 * 当前生效 token 缓存在内存，账户增删改后立即生效、无需重启；
 * 未配置账户时工具回退到 .env 的 GITLAB_TOKEN。
 */
@Component
public class GitLabAccountStore {

    private static final Logger log = LoggerFactory.getLogger(GitLabAccountStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;
    private volatile String cachedToken;

    public GitLabAccountStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS gitlab_accounts (" +
                    "name TEXT PRIMARY KEY," +
                    "config_json TEXT NOT NULL," +
                    "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS gitlab_accounts_meta (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 gitlab_accounts 表失败：{}", ex.getMessage());
        }
        refreshCache();
    }

    /* ---------- 当前生效账户 ---------- */

    /** 生效账户名：显式指定的默认账户优先；未指定且只剩一个账户时直接用它 */
    public String effectiveActive() {
        String explicit = getActive();
        if (explicit != null) {
            return explicit;
        }
        List<GitLabAccount> all = list();
        return all.size() == 1 ? all.get(0).name() : null;
    }

    /** 当前生效的 Access Token（内存缓存）；未配置账户返回 null */
    public String activeToken() {
        return cachedToken;
    }

    private void refreshCache() {
        String name = effectiveActive();
        GitLabAccount acc = name == null ? null : find(name);
        cachedToken = acc == null || acc.token() == null || acc.token().isBlank() ? null : acc.token().trim();
    }

    /* ---------- 显式默认账户 ---------- */

    /** 显式指定的默认账户名；未设置或已失效返回 null */
    public String getActive() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM gitlab_accounts_meta WHERE key = 'active'")) {
            try (ResultSet rs = ps.executeQuery()) {
                String name = rs.next() ? rs.getString("value") : null;
                return name != null && !name.isBlank() && find(name) != null ? name : null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    public void setActive(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO gitlab_accounts_meta(key, value) VALUES('active', ?) " +
                             "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, name == null ? "" : name);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("设置默认 GitLab 账户失败：{}", ex.getMessage());
        }
        refreshCache();
    }

    /* ---------- 账户增删查 ---------- */

    public boolean save(GitLabAccount p) {
        String createdAt = p.createdAt() == null || p.createdAt().isBlank()
                ? java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : p.createdAt();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO gitlab_accounts(name, config_json, created_at) VALUES(?, ?, ?) " +
                             "ON CONFLICT(name) DO UPDATE SET config_json=excluded.config_json")) {
            ps.setString(1, p.name());
            ps.setString(2, mapper.writeValueAsString(new GitLabAccount(p.name(), p.token(), createdAt)));
            ps.setString(3, createdAt);
            ps.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.warn("保存 GitLab 账户失败：{}", ex.getMessage());
            return false;
        } finally {
            refreshCache();
        }
    }

    public List<GitLabAccount> list() {
        List<GitLabAccount> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT config_json FROM gitlab_accounts ORDER BY created_at, name")) {
            while (rs.next()) {
                GitLabAccount p = mapper.readValue(rs.getString("config_json"), GitLabAccount.class);
                if (p != null && p.name() != null) {
                    out.add(p);
                }
            }
        } catch (Exception ex) {
            log.warn("读取 GitLab 账户失败：{}", ex.getMessage());
        }
        return out;
    }

    public GitLabAccount find(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT config_json FROM gitlab_accounts WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.readValue(rs.getString("config_json"), GitLabAccount.class) : null;
            }
        } catch (Exception ex) {
            log.warn("查询 GitLab 账户失败：{}", ex.getMessage());
            return null;
        }
    }

    public boolean delete(String name) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM gitlab_accounts WHERE name = ?")) {
            ps.setString(1, name);
            boolean deleted = ps.executeUpdate() > 0;
            if (deleted && name.equals(getActive())) {
                setActive(null);
            }
            return deleted;
        } catch (Exception ex) {
            log.warn("删除 GitLab 账户失败：{}", ex.getMessage());
            return false;
        } finally {
            refreshCache();
        }
    }

    /** 列表视图：token 打码，只保留末 4 位 */
    public List<Map<String, Object>> listSafe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GitLabAccount p : list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("tokenMasked", mask(p.token()));
            m.put("createdAt", p.createdAt());
            out.add(m);
        }
        return out;
    }

    private static String mask(String token) {
        if (token == null || token.length() <= 8) {
            return "••••••••";
        }
        return "••••••" + token.substring(token.length() - 4);
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }
}
