package com.agentflow.tool;

import com.agentflow.engine.Sqlite;
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
            // 别名存独立列而不是塞进 config_json：config_json 的形状是新旧进程共用的契约，
            // 旧代码用固定字段反序列化，多一个未知字段会让整行（乃至整个列表）读取失败
            try {
                st.execute("ALTER TABLE gitlab_accounts ADD COLUMN authors TEXT");
            } catch (SQLException ignore) { /* 列已存在 */ }
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

    /** 当前生效账户的提交作者别名（git 作者身份与档案不一致时靠它兜住，见 GitLabAccount） */
    public List<String> activeAuthors() {
        String name = effectiveActive();
        GitLabAccount acc = name == null ? null : find(name);
        if (acc == null) {
            return List.of();
        }
        return acc.authors().stream()
                .filter(a -> a != null && !a.isBlank())
                .map(String::trim)
                .toList();
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
        // config_json 固定为 name/token/createdAt 三字段（旧进程的读取契约）；别名走独立列
        com.fasterxml.jackson.databind.node.ObjectNode json = mapper.createObjectNode();
        json.put("name", p.name());
        json.put("token", p.token());
        json.put("createdAt", createdAt);
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO gitlab_accounts(name, config_json, created_at, authors) VALUES(?, ?, ?, ?) " +
                             "ON CONFLICT(name) DO UPDATE SET config_json=excluded.config_json, authors=excluded.authors")) {
            ps.setString(1, p.name());
            ps.setString(2, json.toString());
            ps.setString(3, createdAt);
            ps.setString(4, mapper.writeValueAsString(p.authors() == null ? List.of() : p.authors()));
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
             ResultSet rs = st.executeQuery("SELECT name, config_json, authors FROM gitlab_accounts ORDER BY created_at, name")) {
            while (rs.next()) {
                GitLabAccount p = read(rs);
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
             PreparedStatement ps = c.prepareStatement("SELECT name, config_json, authors FROM gitlab_accounts WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (Exception ex) {
            log.warn("查询 GitLab 账户失败：{}", ex.getMessage());
            return null;
        }
    }

    /** 手工按字段取值而不是绑定 record 反序列化：config_json 里出现未知字段（任何版本写入的形状）都不能让读取失败 */
    private GitLabAccount read(ResultSet rs) throws SQLException {
        try {
            com.fasterxml.jackson.databind.JsonNode n = mapper.readTree(rs.getString("config_json"));
            List<String> authors = new ArrayList<>();
            // 独立列优先；兼容历史上短暂写进 config_json 的 authors 字段
            String column = rs.getString("authors");
            com.fasterxml.jackson.databind.JsonNode fromColumn =
                    column == null || column.isBlank() ? null : mapper.readTree(column);
            com.fasterxml.jackson.databind.JsonNode src = fromColumn != null && fromColumn.isArray() && !fromColumn.isEmpty()
                    ? fromColumn : n.path("authors");
            if (src.isArray()) {
                src.forEach(a -> {
                    String v = a.asText("");
                    if (!v.isBlank()) {
                        authors.add(v);
                    }
                });
            }
            return new GitLabAccount(
                    n.path("name").asText(),
                    n.path("token").asText(),
                    n.path("createdAt").asText(),
                    authors);
        } catch (Exception ex) {
            log.warn("解析 GitLab 账户失败：{}", ex.getMessage());
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

    /** 列表视图：token 打码，只保留末 4 位；authors 供编辑时回填 */
    public List<Map<String, Object>> listSafe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GitLabAccount p : list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("tokenMasked", mask(p.token()));
            m.put("createdAt", p.createdAt());
            m.put("authors", p.authors());
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
        return Sqlite.open(url);
    }
}
