package com.agentflow.notify;

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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 推送通道配置持久化（SQLite notify_channels 表）：由前端「推送通道」弹窗管理。
 * 「选中哪些通道」是一个集合（可多选），存 notify_channels_meta 的 selected 键（JSON 数组），
 * 与通道本身分开——这样改 URL 不会影响选中状态，删通道也能自动从选中集合里摘掉。
 */
@Component
public class NotifyChannelStore {

    private static final Logger log = LoggerFactory.getLogger(NotifyChannelStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String KEY_SELECTED = "selected";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public NotifyChannelStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS notify_channels (" +
                    "name TEXT PRIMARY KEY," +
                    "config_json TEXT NOT NULL," +
                    "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS notify_channels_meta (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 notify_channels 表失败：{}", ex.getMessage());
        }
    }

    public List<NotifyChannel> list() {
        List<NotifyChannel> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT config_json FROM notify_channels ORDER BY created_at, name")) {
            while (rs.next()) {
                NotifyChannel ch = mapper.readValue(rs.getString("config_json"), NotifyChannel.class);
                if (ch != null && ch.name() != null) {
                    out.add(ch);
                }
            }
        } catch (Exception ex) {
            log.warn("读取推送通道失败：{}", ex.getMessage());
        }
        return out;
    }

    public NotifyChannel find(String name) {
        if (name == null) {
            return null;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT config_json FROM notify_channels WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.readValue(rs.getString("config_json"), NotifyChannel.class) : null;
            }
        } catch (Exception ex) {
            log.warn("查询推送通道失败：{}", ex.getMessage());
            return null;
        }
    }

    public boolean isEmpty() {
        return list().isEmpty();
    }

    public boolean save(NotifyChannel ch) {
        // 时间戳补进记录本身再序列化：列表是从 config_json 反序列化的，
        // 只写列不写 JSON 的话读回来 createdAt 会是 null
        NotifyChannel toStore = ch.createdAt() == null || ch.createdAt().isBlank()
                ? new NotifyChannel(ch.name(), ch.type(), ch.url(), LocalDateTime.now().format(TS))
                : ch;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO notify_channels(name, config_json, created_at) VALUES(?, ?, ?) " +
                             "ON CONFLICT(name) DO UPDATE SET config_json=excluded.config_json")) {
            ps.setString(1, toStore.name());
            ps.setString(2, mapper.writeValueAsString(toStore));
            ps.setString(3, toStore.createdAt());
            ps.executeUpdate();
            return true;
        } catch (Exception ex) {
            log.warn("保存推送通道失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 保存并支持改名（name 是主键，改名 = 删旧行 + 写新行）。
     * 原通道若在选中集合里，选中项跟随到新名字，避免改个名就不推了。
     */
    public boolean saveRenamed(NotifyChannel ch, String originalName) {
        if (originalName == null || originalName.isBlank() || originalName.equals(ch.name())) {
            return save(ch);
        }
        Set<String> selected = new LinkedHashSet<>(selected());
        boolean wasSelected = selected.remove(originalName);
        if (!save(ch)) {
            return false;
        }
        deleteRow(originalName);
        if (wasSelected) {
            selected.add(ch.name());
            setSelected(new ArrayList<>(selected));
        }
        return true;
    }

    public boolean delete(String name) {
        boolean deleted = deleteRow(name);
        if (deleted && name != null) {
            // 用未经过滤的原始集合：deleteRow 已经先删了行，再取 selected() 就看不到这个名字了，
            // 会让已删通道名永久残留在 meta 里
            List<String> raw = new ArrayList<>(selectedRaw());
            if (raw.remove(name)) {
                setSelected(raw);
            }
        }
        return deleted;
    }

    private boolean deleteRow(String name) {
        if (name == null) {
            return false;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM notify_channels WHERE name = ?")) {
            ps.setString(1, name);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除推送通道失败：{}", ex.getMessage());
            return false;
        }
    }

    /* ---------- 多选：选中的通道名集合 ---------- */

    /** 选中的通道名；已删除的名字会被自动过滤掉（列表接口不必再清一次） */
    public List<String> selected() {
        Set<String> existing = new LinkedHashSet<>();
        for (NotifyChannel ch : list()) {
            existing.add(ch.name());
        }
        List<String> out = new ArrayList<>();
        for (String name : selectedRaw()) {
            if (existing.contains(name) && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 持久化里的原始值（不过滤已删通道），供删除路径精确摘除、以及测试断言不留垃圾 */
    List<String> selectedRaw() {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM notify_channels_meta WHERE key = ?")) {
            ps.setString(1, KEY_SELECTED);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return List.of();
                }
                return mapper.readValue(rs.getString("value"), new TypeReference<List<String>>() {
                });
            }
        } catch (Exception ex) {
            log.warn("读取通道选中状态失败：{}", ex.getMessage());
            return List.of();
        }
    }

    public void setSelected(List<String> names) {
        List<String> cleaned = new ArrayList<>();
        for (String n : names == null ? List.<String>of() : names) {
            String v = n == null ? "" : n.trim();
            if (!v.isEmpty() && !cleaned.contains(v)) {
                cleaned.add(v);
            }
        }
        try {
            String json = mapper.writeValueAsString(cleaned);
            try (Connection c = open();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO notify_channels_meta(key, value) VALUES(?, ?) " +
                                 "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, KEY_SELECTED);
                ps.setString(2, json);
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.warn("保存通道选中状态失败：{}", ex.getMessage());
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
