package com.agentflow.engine;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
    private final int retentionDays;
    private final int maxRuns;

    public RunStore(String path) {
        this(path, 30, 1000);
    }

    @Autowired
    public RunStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path,
                    @Value("${agentflow.storage.retention-days:30}") int retentionDays,
                    @Value("${agentflow.storage.max-runs:1000}") int maxRuns) {
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
        this.retentionDays = retentionDays;
        this.maxRuns = maxRuns;
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
            // 多对话模型：runs 归属到 session（旧库补列，默认归入 default 对话）。
            // 必须排在引用 session_id 的索引之前，否则新库上索引先报「no such column」而中断初始化。
            try {
                st.execute("ALTER TABLE runs ADD COLUMN session_id TEXT DEFAULT 'default'");
            } catch (SQLException ignore) { /* 列已存在 */ }
            st.execute("CREATE INDEX IF NOT EXISTS idx_events_run ON events(run_id, seq)");
            // taskId 反查（SSE 重连）与按会话分页取消息都靠这两个索引，否则全表扫描
            st.execute("CREATE INDEX IF NOT EXISTS idx_runs_task ON runs(task_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_runs_session ON runs(session_id, id)");
            // 对话自定义标题：不动 runs 结构，列表标题优先取这里，缺省回退首条指令
            st.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "session_id TEXT PRIMARY KEY," +
                    "title TEXT NOT NULL," +
                    "updated_at TEXT NOT NULL)");
        } catch (Exception ex) {
            log.error("初始化 SQLite 失败，历史记录将不可用：{}", ex.getMessage());
            return;
        }
        // 以下都是可选的维护动作：任何一步失败都不该让上面的建表结果连带失效
        try (Connection c = open(); Statement st = c.createStatement()) {
            // 上次进程未正常收尾的任务（停留 running）标记为中断，避免历史里永远"进行中"
            st.executeUpdate("UPDATE runs SET status = 'interrupted' WHERE status = 'running'");
        } catch (Exception ex) {
            log.warn("标记中断任务失败：{}", ex.getMessage());
        }
        try (Connection c = open(); Statement st = c.createStatement()) {
            applyRetention(st);
        } catch (Exception ex) {
            log.warn("应用历史保留策略失败：{}", ex.getMessage());
        }
    }

    /** 保留策略：清理超过 N 天的运行（0=不限），且只保留最新 maxRuns 条（0=不限） */
    private void applyRetention(Statement st) throws SQLException {
        if (retentionDays > 0) {
            st.executeUpdate("DELETE FROM events WHERE run_id IN (SELECT id FROM runs WHERE created_at < " +
                    "datetime('now', 'localtime', '-' || " + retentionDays + " || ' days'))");
            int old = st.executeUpdate("DELETE FROM runs WHERE created_at < " +
                    "datetime('now', 'localtime', '-' || " + retentionDays + " || ' days')");
            if (old > 0) {
                log.info("已按保留策略清理 {} 条超过 {} 天的历史", old, retentionDays);
            }
        }
        if (maxRuns > 0) {
            st.executeUpdate("DELETE FROM events WHERE run_id IN (SELECT id FROM runs WHERE id NOT IN " +
                    "(SELECT id FROM runs ORDER BY id DESC LIMIT " + maxRuns + "))");
            st.executeUpdate("DELETE FROM runs WHERE id NOT IN " +
                    "(SELECT id FROM runs ORDER BY id DESC LIMIT " + maxRuns + ")");
        }
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }

    /**
     * 多语句写操作包在一个事务里，避免中途失败留下孤儿事件或半删状态。
     *
     * <p>连接按操作开关、不常驻：WAL + synchronous=NORMAL 已经消掉了「每次提交一次 fsync」这个
     * 主要开销，而常驻写连接会让进程在整个生命周期里占着库文件（Windows 上连临时目录都删不掉），
     * 代价大于省下的那点建连时间。
     */
    private void inTransaction(String what, SqlOp op) {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                op.run(c);
                c.commit();
            } catch (Exception ex) {
                Sqlite.rollbackQuietly(c);
                log.warn("{} 失败：{}", what, ex.getMessage());
            }
        } catch (Exception ex) {
            log.warn("{} 失败：{}", what, ex.getMessage());
        }
    }

    private interface SqlOp {
        void run(Connection c) throws Exception;
    }

    /** 新建运行记录，返回数据库 id；失败返回 -1（后续持久化自动跳过） */
    public long createRun(String taskId, String command, String sessionId) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO runs(task_id, command, status, created_at, session_id) VALUES(?, ?, 'running', ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, taskId);
            ps.setString(2, command);
            ps.setString(3, java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            ps.setString(4, sessionId == null || sessionId.isBlank() ? "default" : sessionId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception ex) {
            log.warn("写入运行记录失败：{}", ex.getMessage());
            return -1;
        }
    }

    /** 对话列表：一个 session = 一次对话，标题优先取自定义命名、缺省回退首条指令，按最近活动倒序 */
    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT session_id, " +
                "COALESCE((SELECT s.title FROM sessions s WHERE s.session_id = r.session_id), " +
                "(SELECT command FROM runs r2 WHERE r2.session_id = r.session_id ORDER BY r2.id LIMIT 1)) AS title, " +
                "COUNT(*) AS cnt, MAX(created_at) AS last_time, " +
                "SUM(CASE WHEN status = 'error' THEN 1 ELSE 0 END) AS err_cnt, " +
                "SUM(CASE WHEN status = 'running' THEN 1 ELSE 0 END) AS run_cnt " +
                "FROM runs r GROUP BY session_id ORDER BY MAX(id) DESC LIMIT 100";
        try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("session_id"));
                m.put("title", rs.getString("title"));
                m.put("count", rs.getInt("cnt"));
                m.put("lastTime", rs.getString("last_time"));
                m.put("status", rs.getInt("run_cnt") > 0 ? "running"
                        : rs.getInt("err_cnt") > 0 ? "error" : "done");
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("读取对话列表失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 某个对话内的全部消息（按时间正序，供整段回放） */
    public List<Map<String, Object>> listRunsBySession(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, command, summary, status, created_at FROM runs WHERE session_id = ? ORDER BY id ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                collectRuns(rs, out);
            }
        } catch (Exception ex) {
            log.warn("读取对话消息失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 分页页结果：runs 按时间正序，hasMore 表示更早的消息还有 */
    public record SessionPage(List<Map<String, Object>> runs, boolean hasMore) {
    }

    /**
     * 按会话分页读消息（从最新往回取一页，返回时恢复正序）。
     * beforeId 为空取最新一页；多取一条探测 hasMore。
     * 这里连 output 一起取，让调用方无需再逐条 getRun 补字段。
     */
    public SessionPage listRunsBySessionPage(String sessionId, int limit, Long beforeId) {
        List<Map<String, Object>> desc = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT id, command, summary, output, status, created_at FROM runs WHERE session_id = ?");
        if (beforeId != null) {
            sql.append(" AND id < ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, sessionId);
            if (beforeId != null) {
                ps.setLong(i++, beforeId);
            }
            ps.setInt(i, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                collectRunsWithOutput(rs, desc);
            }
        } catch (Exception ex) {
            log.warn("分页读取对话消息失败：{}", ex.getMessage());
        }
        boolean hasMore = desc.size() > limit;
        if (hasMore) {
            desc = new ArrayList<>(desc.subList(0, limit));
        }
        Collections.reverse(desc);
        return new SessionPage(desc, hasMore);
    }

    /** 重命名对话：空白标题表示清除自定义命名、恢复自动标题；对话不存在返回 false */
    public boolean renameSession(String sessionId, String title) {
        try (Connection c = open()) {
            try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM runs WHERE session_id = ? LIMIT 1")) {
                check.setString(1, sessionId);
                try (ResultSet rs = check.executeQuery()) {
                    if (!rs.next()) return false;
                }
            }
            if (title == null || title.isBlank()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
                return true;
            }
            String now = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO sessions(session_id, title, updated_at) VALUES(?, ?, ?) " +
                            "ON CONFLICT(session_id) DO UPDATE SET title = excluded.title, updated_at = excluded.updated_at")) {
                ps.setString(1, sessionId);
                ps.setString(2, title);
                ps.setString(3, now);
                ps.executeUpdate();
            }
            return true;
        } catch (Exception ex) {
            log.warn("重命名对话失败：{}", ex.getMessage());
            return false;
        }
    }

    /** 删除整个对话（含全部消息与事件） */
    public void deleteSession(String sessionId) {
        inTransaction("删除对话", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM events WHERE run_id IN (SELECT id FROM runs WHERE session_id = ?)")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM runs WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
        });
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

    /** 历史列表（新→旧，可按指令/摘要关键词过滤），供前端抽屉展示 */
    public List<Map<String, Object>> listRuns(int limit, String keyword) {
        List<Map<String, Object>> out = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim();
        String sql;
        if (kw.isEmpty()) {
            sql = "SELECT id, command, summary, status, created_at FROM runs ORDER BY id DESC LIMIT " +
                    Math.max(1, Math.min(limit, 200));
            try (Connection c = open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                collectRuns(rs, out);
            } catch (Exception ex) {
                log.warn("读取历史列表失败：{}", ex.getMessage());
            }
            return out;
        }
        sql = "SELECT id, command, summary, status, created_at FROM runs WHERE command LIKE ? ESCAPE '\\' " +
                "OR summary LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 200));
        String like = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, like);
            ps.setString(2, like);
            try (ResultSet rs = ps.executeQuery()) {
                collectRuns(rs, out);
            }
        } catch (Exception ex) {
            log.warn("读取历史列表失败：{}", ex.getMessage());
        }
        return out;
    }

    private static void collectRuns(ResultSet rs, List<Map<String, Object>> out) throws SQLException {
        while (rs.next()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("command", rs.getString("command"));
            m.put("summary", rs.getString("summary"));
            m.put("status", rs.getString("status"));
            m.put("createdAt", rs.getString("created_at"));
            out.add(m);
        }
    }

    /** 供分页回放使用：比 collectRuns 多带 output，字段与 getRun 保持一致 */
    private static void collectRunsWithOutput(ResultSet rs, List<Map<String, Object>> out) throws SQLException {
        while (rs.next()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("command", rs.getString("command"));
            m.put("summary", rs.getString("summary"));
            m.put("output", rs.getString("output"));
            m.put("status", rs.getString("status"));
            m.put("createdAt", rs.getString("created_at"));
            out.add(m);
        }
    }

    /** 单次运行的完整事件流，供回放 */
    public Map<String, Object> getRun(long runId) {
        Map<String, Object> run = getRunMeta(runId);
        if (run == null) {
            return null;
        }
        run.put("events", listEvents(runId, -1));
        return run;
    }

    /** 单次运行的元信息（不含事件流），供需要自行批量取事件的调用方使用 */
    public Map<String, Object> getRunMeta(long runId) {
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
        return run;
    }

    /**
     * 一批运行的事件流（按 run_id 分组，组内按 seq 升序）。
     * 会话回放原本逐条 getRun，一次请求放大成 limit+1 次查询，这里收敛为一次 IN 查询。
     */
    public Map<Long, List<Map<String, Object>>> listEventsByRunIds(Collection<Long> runIds) {
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        if (runIds == null || runIds.isEmpty()) {
            return grouped;
        }
        for (Long id : runIds) {
            grouped.put(id, new ArrayList<>());
        }
        String sql = "SELECT run_id, event, data FROM events WHERE run_id IN (" +
                String.join(",", Collections.nCopies(runIds.size(), "?")) + ") ORDER BY run_id, seq";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Long id : runIds) {
                ps.setLong(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("event", rs.getString("event"));
                    e.put("data", mapper.readTree(rs.getString("data")));
                    grouped.computeIfAbsent(rs.getLong("run_id"), k -> new ArrayList<>()).add(e);
                }
            }
        } catch (Exception ex) {
            log.warn("批量读取事件流失败：{}", ex.getMessage());
        }
        return grouped;
    }

    public void deleteRun(long runId) {
        inTransaction("删除运行记录", c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM events WHERE run_id = ?")) {
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM runs WHERE id = ?")) {
                ps.setLong(1, runId);
                ps.executeUpdate();
            }
        });
    }

    /** afterSeq 之后的存量事件（断线续传补发用），data 反序列化为 JsonNode */
    public List<Map<String, Object>> listEvents(long runId, int afterSeq) {
        List<Map<String, Object>> events = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event, data FROM events WHERE run_id = ? AND seq > ? ORDER BY seq")) {
            ps.setLong(1, runId);
            ps.setInt(2, afterSeq);
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
        return events;
    }

    /** 会话不在内存时按 taskId 反查最近一次运行 id */
    public Long findRunIdByTaskId(String taskId) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM runs WHERE task_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (Exception ex) {
            log.warn("按 taskId 查询运行记录失败：{}", ex.getMessage());
            return null;
        }
    }

    public void clearAll() {
        inTransaction("清空历史", c -> {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM events");
                st.executeUpdate("DELETE FROM runs");
            }
        });
    }
}
