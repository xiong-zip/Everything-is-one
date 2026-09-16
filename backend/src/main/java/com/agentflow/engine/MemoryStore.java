package com.agentflow.engine;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨会话长期记忆（SQLite agent_memory 表）：只存用户偏好与事实（常用集群、署名、习惯说法），
 * 不存可随时查询的运维数据。来源两条路：显式「记住/忘记」指令（确定性，不花 LLM）
 * 与任务收尾后的 LLM 自动提取（MemoryStore 只管存取，提取在 AgentEngine）。
 * meta 表存 auto-extract 开关；总开关由 agentflow.memory.enabled 控制（引擎侧判断）。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    public record MemoryItem(long id, String content, String createdAt, String updatedAt) {
    }

    /** 记忆条数上限：再多也只是在提示词里放不下，提取侧会先收口 */
    private static final int MAX_ITEMS = 40;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;

    public MemoryStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS agent_memory (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "content TEXT NOT NULL UNIQUE," +
                    "created_at TEXT NOT NULL," +
                    "updated_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS agent_memory_meta (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 agent_memory 表失败：{}", ex.getMessage());
        }
    }

    public List<MemoryItem> list() {
        List<MemoryItem> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, content, created_at, updated_at FROM agent_memory ORDER BY id")) {
            while (rs.next()) {
                out.add(new MemoryItem(rs.getLong("id"), rs.getString("content"),
                        rs.getString("created_at"), rs.getString("updated_at")));
            }
        } catch (Exception ex) {
            log.warn("读取记忆失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 新增一条（trim、去重）；返回是否真的写入（重复或超上限返回 false，重复不覆盖原时间） */
    public boolean add(String content) {
        String v = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        if (v.isEmpty() || v.length() > 200) {
            return false;
        }
        if (list().size() >= MAX_ITEMS) {
            log.info("记忆条数已达上限 {}，忽略新增：{}", MAX_ITEMS, v);
            return false;
        }
        String now = LocalDateTime.now().format(TS);
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR IGNORE INTO agent_memory(content, created_at, updated_at) VALUES(?, ?, ?)")) {
            ps.setString(1, v);
            ps.setString(2, now);
            ps.setString(3, now);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("保存记忆失败：{}", ex.getMessage());
            return false;
        }
    }

    public boolean delete(long id) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM agent_memory WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除记忆失败：{}", ex.getMessage());
            return false;
        }
    }

    /** 按内容删除（精确匹配），返回是否删到 */
    public boolean deleteByContent(String content) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM agent_memory WHERE content = ?")) {
            ps.setString(1, content == null ? "" : content.trim());
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("按内容删除记忆失败：{}", ex.getMessage());
            return false;
        }
    }

    /** 子串模糊匹配（「忘记」指令用），大小写不敏感 */
    public List<MemoryItem> findBySubstring(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.isEmpty()) {
            return List.of();
        }
        List<MemoryItem> out = new ArrayList<>();
        for (MemoryItem m : list()) {
            if (m.content().toLowerCase().contains(kw)) {
                out.add(m);
            }
        }
        return out;
    }

    public int clear() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            return st.executeUpdate("DELETE FROM agent_memory");
        } catch (Exception ex) {
            log.warn("清空记忆失败：{}", ex.getMessage());
            return 0;
        }
    }

    /* ---------- 自动提取开关（meta） ---------- */

    public boolean isAutoExtract() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM agent_memory_meta WHERE key = 'auto_extract'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return !rs.next() || !"0".equals(rs.getString("value"));
            }
        } catch (Exception ex) {
            return true;
        }
    }

    public void setAutoExtract(boolean enabled) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO agent_memory_meta(key, value) VALUES('auto_extract', ?) " +
                             "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, enabled ? "1" : "0");
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("设置自动提取开关失败：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }
}
