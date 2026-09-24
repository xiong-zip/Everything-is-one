package com.agentflow.wecom;

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
 * 企微能力清单与状态的持久化（SQLite wecom_capabilities / wecom_state 表，与任务历史同库）。
 * 能力清单来自 wecom-cli 的 --help 输出，刷新一次要跑几十个子进程，
 * 落库后面板与 wecom.call 的参数提示都不必重跑（启动零子进程）。
 */
@Component
public class WecomStore {

    private static final Logger log = LoggerFactory.getLogger(WecomStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 一个可调用的方法（service + method 两段式命名，desc 来自 CLI 帮助） */
    public record Capability(String service, String method, String description) {
    }

    private final String url;

    public WecomStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建企微存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS wecom_capabilities (" +
                    "service TEXT NOT NULL," +
                    "method TEXT NOT NULL," +
                    "description TEXT DEFAULT ''," +
                    "PRIMARY KEY(service, method))");
            st.execute("CREATE TABLE IF NOT EXISTS wecom_state (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL DEFAULT '')");
        } catch (Exception ex) {
            log.error("初始化企微表失败：{}", ex.getMessage());
        }
    }

    public void replaceCapabilities(List<Capability> caps) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM wecom_capabilities");
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO wecom_capabilities(service, method, description) VALUES(?, ?, ?)")) {
                    for (Capability cap : caps) {
                        ps.setString(1, cap.service());
                        ps.setString(2, cap.method());
                        ps.setString(3, cap.description() == null ? "" : cap.description());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            c.commit();
        } catch (Exception ex) {
            log.warn("保存企微能力清单失败：{}", ex.getMessage());
        }
    }

    public List<Capability> capabilities() {
        List<Capability> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM wecom_capabilities ORDER BY service, method")) {
            while (rs.next()) {
                out.add(new Capability(rs.getString("service"), rs.getString("method"), nz(rs.getString("description"))));
            }
        } catch (Exception ex) {
            log.warn("读取企微能力清单失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 按 service 分组的面板视图：{doc: [{method, description}...], ...} */
    public Map<String, List<Map<String, String>>> capabilitiesByService() {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (Capability cap : capabilities()) {
            out.computeIfAbsent(cap.service(), k -> new ArrayList<>())
                    .add(Map.of("method", cap.method(), "description", cap.description()));
        }
        return out;
    }

    public String state(String key) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT value FROM wecom_state WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? nz(rs.getString(1)) : "";
            }
        } catch (Exception ex) {
            return "";
        }
    }

    public void putState(String key, String value) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO wecom_state(key, value) VALUES(?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("写入企微状态失败：{}", ex.getMessage());
        }
    }

    public void recordRefresh(String error) {
        if (error == null || error.isBlank()) {
            putState("lastRefreshAt", LocalDateTime.now().format(TS));
            putState("lastError", "");
        } else {
            // 失败只记原因、保留上次成功时间：「上次成功是三天前」比「从来没成功过」信息量大
            putState("lastError", error.length() > 300 ? error.substring(0, 300) + "…" : error);
        }
    }

    private Connection open() throws java.sql.SQLException {
        return Sqlite.open(url);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
