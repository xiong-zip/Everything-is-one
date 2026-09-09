package com.agentflow.schedule;

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

/** 定时任务执行历史（SQLite schedule_runs 表，与任务历史同库） */
@Component
public class ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public ScheduleStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS schedule_runs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "trigger TEXT NOT NULL," +
                    "task_id TEXT DEFAULT ''," +
                    "status TEXT DEFAULT ''," +
                    "summary TEXT DEFAULT ''," +
                    "output TEXT DEFAULT ''," +
                    "pushed INTEGER DEFAULT 0," +
                    "created_at TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 schedule_runs 表失败：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    public long record(String trigger, String taskId, String status, String summary, String output, boolean pushed) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO schedule_runs(trigger, task_id, status, summary, output, pushed, created_at) " +
                             "VALUES(?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, trigger);
            ps.setString(2, taskId == null ? "" : taskId);
            ps.setString(3, status == null ? "" : status);
            ps.setString(4, summary == null ? "" : summary);
            ps.setString(5, output == null ? "" : output);
            ps.setInt(6, pushed ? 1 : 0);
            ps.setString(7, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception ex) {
            log.warn("写入定时执行记录失败：{}", ex.getMessage());
            return -1;
        }
    }

    /** 最近若干次执行（新→旧），output 截断避免列表过重 */
    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT trigger, task_id, status, summary, output, pushed, created_at " +
                             "FROM schedule_runs ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 50)))) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("trigger", rs.getString("trigger"));
                    m.put("taskId", rs.getString("task_id"));
                    m.put("status", rs.getString("status"));
                    m.put("summary", rs.getString("summary"));
                    String output = rs.getString("output");
                    m.put("output", output != null && output.length() > 400 ? output.substring(0, 400) + "…" : output);
                    m.put("pushed", rs.getInt("pushed") == 1);
                    m.put("createdAt", rs.getString("created_at"));
                    out.add(m);
                }
            }
        } catch (Exception ex) {
            log.warn("读取定时执行记录失败：{}", ex.getMessage());
        }
        return out;
    }
}
