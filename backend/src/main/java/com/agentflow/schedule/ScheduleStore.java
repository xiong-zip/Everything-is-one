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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务与执行历史（SQLite schedule_tasks / schedule_runs 表，与任务历史同库）。
 *
 * <p>一个「定时任务」就是一条指令（command 唯一）：执行记录按 command 归类到对应任务下，
 * 所以界面上「最近执行」是分组的，每个分类能单独开关自动执行、单独删除。
 */
@Component
public class ScheduleStore {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 每个任务在列表里带回多少条执行记录 */
    private static final int RUNS_PER_TASK = 20;

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
            st.execute("CREATE TABLE IF NOT EXISTS schedule_meta (" +
                    "key TEXT PRIMARY KEY," +
                    "value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS schedule_tasks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "command TEXT NOT NULL UNIQUE," +
                    "enabled INTEGER NOT NULL DEFAULT 1," +
                    "created_at TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 schedule 表失败：{}", ex.getMessage());
        }
        // 老库升级：执行记录原本没有 command 列，没有它就无法按任务归类
        try (Connection c = open()) {
            ensureColumn(c, "schedule_runs", "command", "command TEXT DEFAULT ''");
        } catch (Exception ex) {
            log.warn("升级 schedule_runs 表失败：{}", ex.getMessage());
        }
    }

    /** 幂等加列：先查 PRAGMA，缺了才 ALTER——项目没有统一 migration，这是最省事的自愈方式 */
    private static void ensureColumn(Connection c, String table, String column, String ddl) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + ddl);
            log.info("已为 {} 表补充 {} 列", table, column);
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /* ---------- 定时任务 ---------- */

    /** 一个定时任务：指令 + 是否自动执行 */
    public record Task(long id, String command, boolean enabled, String createdAt) {
    }

    public List<Task> tasks() {
        List<Task> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, command, enabled, created_at FROM schedule_tasks ORDER BY id")) {
            while (rs.next()) {
                out.add(new Task(rs.getLong("id"), rs.getString("command"),
                        rs.getInt("enabled") == 1, rs.getString("created_at")));
            }
        } catch (Exception ex) {
            log.warn("读取定时任务失败：{}", ex.getMessage());
        }
        return out;
    }

    public Task findTask(long id) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, command, enabled, created_at FROM schedule_tasks WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Task(rs.getLong("id"), rs.getString("command"),
                        rs.getInt("enabled") == 1, rs.getString("created_at")) : null;
            }
        } catch (Exception ex) {
            log.warn("查询定时任务失败：{}", ex.getMessage());
            return null;
        }
    }

    public Task findTaskByCommand(String command) {
        if (command == null) {
            return null;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, command, enabled, created_at FROM schedule_tasks WHERE command = ?")) {
            ps.setString(1, command);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Task(rs.getLong("id"), rs.getString("command"),
                        rs.getInt("enabled") == 1, rs.getString("created_at")) : null;
            }
        } catch (Exception ex) {
            log.warn("按指令查询定时任务失败：{}", ex.getMessage());
            return null;
        }
    }

    /** 新增任务；指令已存在时返回已存在的那个（指令是唯一键，不做重复建卡） */
    public Task createTask(String command, boolean enabled) {
        Task existing = findTaskByCommand(command);
        if (existing != null) {
            return existing;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO schedule_tasks(command, enabled, created_at) VALUES(?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, command);
            ps.setInt(2, enabled ? 1 : 0);
            ps.setString(3, LocalDateTime.now().format(TS));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? findTask(rs.getLong(1)) : null;
            }
        } catch (Exception ex) {
            log.warn("新增定时任务失败：{}", ex.getMessage());
            return null;
        }
    }

    /** 改名/改指令；新指令已被别的任务占用时返回 false（由调用方提示） */
    public boolean updateTask(long id, String command, boolean enabled) {
        Task self = findTask(id);
        if (self == null) {
            return false;
        }
        Task other = findTaskByCommand(command);
        if (other != null && other.id() != id) {
            return false;
        }
        try (Connection c = open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE schedule_tasks SET command = ?, enabled = ? WHERE id = ?")) {
                ps.setString(1, command);
                ps.setInt(2, enabled ? 1 : 0);
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            // 指令是执行记录的归类键，改了要一起改，否则老记录会变成没人认领的孤儿
            if (!self.command().equals(command)) {
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE schedule_runs SET command = ? WHERE command = ?")) {
                    up.setString(1, command);
                    up.setString(2, self.command());
                    up.executeUpdate();
                }
            }
            return true;
        } catch (Exception ex) {
            log.warn("更新定时任务失败：{}", ex.getMessage());
            return false;
        }
    }

    /** 只切开关，不动指令（界面上的单个任务开关） */
    public void setTaskEnabled(long id, boolean enabled) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("UPDATE schedule_tasks SET enabled = ? WHERE id = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("更新任务开关失败：{}", ex.getMessage());
        }
    }

    /** 删除任务；默认连它的执行记录一起删（界面上「删除分类」的语义） */
    public boolean deleteTask(long id, boolean withRuns) {
        Task self = findTask(id);
        if (self == null) {
            return false;
        }
        try (Connection c = open()) {
            if (withRuns) {
                // 按 command 删（执行记录的归类键就是它）；task_id 那列存的是 AgentEngine 的任务 UUID，不是这里的主键
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM schedule_runs WHERE command = ?")) {
                    ps.setString(1, self.command());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM schedule_tasks WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (Exception ex) {
            log.warn("删除定时任务失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 老库升级：升级前只有一个全局指令，所有历史记录的 command 都是空的。
     * 用当时的指令回填是准确的（那时确实只跑这一条），不回填的话这些记录会变成无归属的孤儿。
     */
    public int backfillRunsCommand(String command) {
        if (command == null || command.isBlank()) {
            return 0;
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE schedule_runs SET command = ? WHERE command IS NULL OR command = ''")) {
            ps.setString(1, command);
            return ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("回填历史执行记录的指令失败：{}", ex.getMessage());
            return 0;
        }
    }

    /* ---------- 执行记录 ---------- */

    public long record(String trigger, String taskId, String status, String summary, String output,
                       boolean pushed, String command) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO schedule_runs(trigger, task_id, status, summary, output, pushed, created_at, command) " +
                             "VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, trigger);
            ps.setString(2, taskId == null ? "" : taskId);
            ps.setString(3, status == null ? "" : status);
            ps.setString(4, summary == null ? "" : summary);
            ps.setString(5, output == null ? "" : output);
            ps.setInt(6, pushed ? 1 : 0);
            ps.setString(7, LocalDateTime.now().format(TS));
            ps.setString(8, command == null ? "" : command);
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
                     "SELECT trigger, task_id, status, summary, output, pushed, created_at, command " +
                             "FROM schedule_runs ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 50)))) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(runRow(rs));
                }
            }
        } catch (Exception ex) {
            log.warn("读取定时执行记录失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 某个任务名下的最近执行记录（界面展开「最近执行」用） */
    private List<Map<String, Object>> runsOf(String command) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT trigger, task_id, status, summary, output, pushed, created_at, command " +
                             "FROM schedule_runs WHERE command = ? ORDER BY id DESC LIMIT " + RUNS_PER_TASK)) {
            ps.setString(1, command);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(runRow(rs));
                }
            }
        } catch (Exception ex) {
            log.warn("读取任务执行记录失败：{}", ex.getMessage());
        }
        return out;
    }

    private static Map<String, Object> runRow(ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trigger", rs.getString("trigger"));
        m.put("taskId", rs.getString("task_id"));
        m.put("status", rs.getString("status"));
        m.put("summary", rs.getString("summary"));
        String output = rs.getString("output");
        m.put("output", output != null && output.length() > 400 ? output.substring(0, 400) + "…" : output);
        m.put("pushed", rs.getInt("pushed") == 1);
        m.put("createdAt", rs.getString("created_at"));
        m.put("command", rs.getString("command"));
        return m;
    }

    /**
     * 界面要的形态：任务列表，每个任务带上自己的执行记录与统计。
     * 有执行记录但任务行已不存在的情况不会出现（删任务会连记录一起删）。
     */
    public List<Map<String, Object>> taskSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : tasks()) {
            List<Map<String, Object>> runs = runsOf(t.command());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("command", t.command());
            m.put("enabled", t.enabled());
            m.put("createdAt", t.createdAt());
            m.put("runs", runs);
            m.put("runCount", runs.size());
            m.put("lastRunAt", runs.isEmpty() ? "" : runs.get(0).get("createdAt"));
            m.put("lastStatus", runs.isEmpty() ? "" : runs.get(0).get("status"));
            m.put("lastPushed", !runs.isEmpty() && Boolean.TRUE.equals(runs.get(0).get("pushed")));
            out.add(m);
        }
        return out;
    }

    /** 删除单条执行记录 */
    public boolean deleteRun(String taskId, String createdAt) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM schedule_runs WHERE task_id = ? AND created_at = ?")) {
            ps.setString(1, taskId == null ? "" : taskId);
            ps.setString(2, createdAt == null ? "" : createdAt);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除执行记录失败：{}", ex.getMessage());
            return false;
        }
    }

    /* ---------- 界面可改的配置（schedule_meta，改完立即生效，无需重启） ---------- */

    /** 读配置项；<b>从未在界面设置过时返回 null</b>，由调用方回退到 .env，保证老配置继续有效 */
    private String readMeta(String key) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM schedule_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (Exception ex) {
            log.warn("读取配置项 {} 失败：{}", key, ex.getMessage());
            return null;
        }
    }

    /** 写配置项；value 为 null 表示删除该项（即回到 .env 默认值） */
    private void writeMeta(String key, String value) {
        try (Connection c = open()) {
            if (value == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM schedule_meta WHERE key = ?")) {
                    ps.setString(1, key);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schedule_meta(key, value) VALUES(?, ?) " +
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        } catch (Exception ex) {
            log.warn("保存配置项 {} 失败：{}", key, ex.getMessage());
        }
    }

    /** 总开关；未设置过返回 null */
    public Boolean readEnabled() {
        String v = readMeta("enabled");
        return v == null ? null : "1".equals(v);
    }

    public void writeEnabled(boolean enabled) {
        writeMeta("enabled", enabled ? "1" : "0");
    }

    /** 老库里的全局指令：只在首次升级时用来生成第一个任务并回填历史，之后以任务表为准 */
    public String readCommand() {
        return readMeta("command");
    }

    /** 推送形态（纯文本/markdown/摘要+附件）的界面设置；未设置过返回 null */
    public String readNotifyMode() {
        return readMeta("notify_mode");
    }

    public void writeNotifyMode(String mode) {
        writeMeta("notify_mode", mode);
    }
}
