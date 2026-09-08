package com.agentflow.engine;

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
 * 任务历史持久化（SQLite）：一次运行 = runs 一行 + events 全量事件流。
 * 回放即按序重发存量事件，与实时 SSE 走同一条前端渲染管线。
 * 持久化失败只记日志，不影响任务执行本身。
 */
@Component
public class RunStore {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String url;

    public RunStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
        String dbPath = path == null || path.isBlank() ? "./data/agentflow.db" : path.trim();
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
            st.execute("CREATE TABLE IF NOT EXISTS runs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "task_id TEXT NOT NULL," +
                    "command TEXT NOT NULL," +
                    "summary TEXT DEFAULT ''," +
                    "output TEXT DEFAULT ''," +
                    "status TEXT DEFAULT 'running'," +
                    "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "run_id INTEGER NOT NULL," +
                    "seq INTEGER NOT NULL," +
                    "event TEXT NOT NULL," +
                    "data TEXT NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_run ON events(run_id, seq)");
        } catch (Exception ex) {
            log.error("初始化 SQLite 失败，历史记录将不可用：{}", ex.getMessage());
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** 新建运行记录，返回数据库 id；失败返回 -1（后续持久化自动跳过） */
    public long createRun(String taskId, String command) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO runs(task_id, command, status, created_at) VALUES(?, ?, 'running', ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, taskId);
            ps.setString(2, command);
            ps.setString(3, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception ex) {
            log.warn("写入运行记录失败：{}", ex.getMessage());
            return -1;
        }
    }

    public void saveEvent(long runId, int seq, String event, Object data) {
        if (runId < 0) return;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO events(run_id, seq, event, data) VALUES(?, ?, ?, ?)")) {
            ps.setLong(1, runId);
            ps.setInt(2, seq);
            ps.setString(3, event);
            ps.setString(4, mapper.writeValueAsString(data));
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("写入事件失败：{}", ex.getMessage());
        }
    }

    public void finishRun(long runId, String status, String summary, String output) {
        if (runId < 0) return;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE runs SET status = ?, summary = ?, output = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, summary == null ? "" : summary);
            ps.setString(3, output == null ? "" : output);
            ps.setLong(4, runId);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("更新运行状态失败：{}", ex.getMessage());
        }
    }

    /** 历史列表（新→旧），供前端抽屉展示 */
    public List<Map<String, Object>> listRuns(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT id, command, summary, status, created_at FROM runs ORDER BY id DESC LIMIT " +
                Math.max(1, Math.min(limit, 200));
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("command", rs.getString("command"));
                m.put("summary", rs.getString("summary"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("读取历史列表失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 单次运行的完整事件流，供回放 */
    public Map<String, Object> getRun(long runId) {
        Map<String, Object> run = null;
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, command, summary, output, status, created_at FROM runs WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    run = new LinkedHashMap<>();
                    run.put("id", rs.getLong("id"));
                    run.put("command", rs.getString("command"));
                    run.put("summary", rs.getString("summary"));
                    run.put("output", rs.getString("output"));
                    run.put("status", rs.getString("status"));
                    run.put("createdAt", rs.getString("created_at"));
                }
            }
        } catch (Exception ex) {
            log.warn("读取运行记录失败：{}", ex.getMessage());
        }
        if (run == null) {
            return null;
        }
        List<Map<String, Object>> events = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event, data FROM events WHERE run_id = ? ORDER BY seq")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("event", rs.getString("event"));
                    e.put("data", mapper.readTree(rs.getString("data")));
                    events.add(e);
                }
            }
        } catch (Exception ex) {
            log.warn("读取事件流失败：{}", ex.getMessage());
        }
        run.put("events", events);
        return run;
    }

    public void deleteRun(long runId) {
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM events WHERE run_id = ?")) {
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM runs WHERE id = ?")) {
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.warn("删除运行记录失败：{}", ex.getMessage());
        }
    }

    public void clearAll() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM events");
            st.executeUpdate("DELETE FROM runs");
        } catch (Exception ex) {
            log.warn("清空历史失败：{}", ex.getMessage());
        }
    }
}
