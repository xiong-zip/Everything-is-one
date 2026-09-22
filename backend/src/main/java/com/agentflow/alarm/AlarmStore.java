package com.agentflow.alarm;

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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警值守记录（SQLite alarm_runs 表，与任务历史同库）。
 *
 * <p>一张表兼两用，是因为两者生命周期完全一致：<b>去重</b>要问「这个告警最近有没有来过」，
 * <b>回看</b>要问「昨晚自动排查了哪些、结论是什么」。拆两张表反而要维护两份写入。
 *
 * <p>去重时间比较交给 SQLite 的 {@code datetime('now','localtime')}，避免应用时区与
 * 存库时间格式不一致导致的静默失效——写入格式与 {@code datetime()} 的输出格式一致
 * （{@code yyyy-MM-dd HH:mm:ss}），字符串比较即等价于时间比较。
 */
@Component
public class AlarmStore {

    private static final Logger log = LoggerFactory.getLogger(AlarmStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;
    private final int maxRows;

    public AlarmStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path,
                      @Value("${agentflow.alarm.max-records:1000}") int maxRows) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建告警记录存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
        this.maxRows = maxRows <= 0 ? 1000 : maxRows;
    }

    /**
     * 一条值守记录：受理时的告警信息 + 排查完成后的结论。
     * state 取值 running / done / skipped / error，中文标签由前端映射（后端不掺展示逻辑）。
     */
    public record Record(long id, String receivedAt, String source, String alertName, String severity,
                         String service, String traceId, String alarmStatus, String dedupKey,
                         String state, String runTaskId, String summary, String output, boolean pushed,
                         long elapsedMs) {
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS alarm_runs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "received_at TEXT NOT NULL," +
                    "source TEXT DEFAULT ''," +
                    "alert_name TEXT DEFAULT ''," +
                    "severity TEXT DEFAULT ''," +
                    "service TEXT DEFAULT ''," +
                    "trace_id TEXT DEFAULT ''," +
                    "alarm_status TEXT DEFAULT ''," +
                    "dedup_key TEXT DEFAULT ''," +
                    "state TEXT DEFAULT 'running'," +
                    "run_task_id TEXT DEFAULT ''," +
                    "summary TEXT DEFAULT ''," +
                    "output TEXT DEFAULT ''," +
                    "pushed INTEGER DEFAULT 0," +
                    "elapsed_ms INTEGER DEFAULT 0," +
                    "raw TEXT DEFAULT '')");
            st.execute("CREATE INDEX IF NOT EXISTS idx_alarm_runs_at ON alarm_runs(received_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_alarm_runs_key ON alarm_runs(dedup_key, received_at)");
            st.executeUpdate("DELETE FROM alarm_runs WHERE id NOT IN " +
                    "(SELECT id FROM alarm_runs ORDER BY id DESC LIMIT " + maxRows + ")");
        } catch (Exception ex) {
            log.error("初始化 alarm_runs 表失败，告警值守记录将不可用：{}", ex.getMessage());
        }
    }

    /** 受理一条告警，返回记录 id（失败返回 -1，调用方据此降级为「不落库但照常排查」） */
    public long insert(AlarmEvent event, String state) {
        String now = LocalDateTime.now().format(TS);
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO alarm_runs(received_at, source, alert_name, severity, service, trace_id," +
                        "alarm_status, dedup_key, state, raw) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, now);
            ps.setString(2, event.source());
            ps.setString(3, event.alertName());
            ps.setString(4, event.severityOrInfo());
            ps.setString(5, event.service());
            ps.setString(6, event.traceId());
            ps.setString(7, event.status());
            ps.setString(8, event.dedupKey());
            ps.setString(9, state);
            ps.setString(10, event.raw());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception ex) {
            log.warn("写入告警记录失败：{}", ex.getMessage());
            return -1;
        }
    }

    /** 排查结束回填结论 */
    public void finish(long id, String state, String runTaskId, String summary, String output,
                       boolean pushed, long elapsedMs) {
        if (id <= 0) {
            return;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE alarm_runs SET state=?, run_task_id=?, summary=?, output=?, pushed=?, elapsed_ms=?" +
                        " WHERE id=?")) {
            ps.setString(1, state);
            ps.setString(2, nz(runTaskId));
            ps.setString(3, nz(summary));
            ps.setString(4, nz(output));
            ps.setInt(5, pushed ? 1 : 0);
            ps.setLong(6, elapsedMs);
            ps.setLong(7, id);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("回填告警排查结果失败：{}", ex.getMessage());
        }
    }

    /**
     * 窗口内同一条告警来过几次（不含正在受理的这次）。
     * 时间是字符串比较：写入格式与 SQLite datetime() 输出一致，故可直接比。
     */
    public int recentCount(String dedupKey, int windowSeconds) {
        if (dedupKey == null || dedupKey.isBlank() || windowSeconds <= 0) {
            return 0;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM alarm_runs WHERE dedup_key=? AND received_at >= datetime('now','localtime',?)")) {
            ps.setString(1, dedupKey);
            ps.setString(2, "-" + windowSeconds + " seconds");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception ex) {
            // 查询失败按「没来过」处理：宁可多排查一次，也不要因为去重查询报错而漏掉告警
            log.warn("告警去重查询失败：{}", ex.getMessage());
            return 0;
        }
    }

    public List<Record> list(int limit, int offset) {
        List<Record> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM alarm_runs ORDER BY id DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 200)));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (Exception ex) {
            log.warn("读取告警记录失败：{}", ex.getMessage());
        }
        return out;
    }

    public int count() {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM alarm_runs")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception ex) {
            log.warn("统计告警记录失败：{}", ex.getMessage());
            return 0;
        }
    }

    /** 汇总：各状态条数与 Top 服务，供面板概览 */
    public Map<String, Object> stats() {
        Map<String, Integer> byState = new LinkedHashMap<>();
        Map<String, Integer> byService = new LinkedHashMap<>();
        int total = 0;
        int pushed = 0;
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT state, service, pushed FROM alarm_runs")) {
            while (rs.next()) {
                total++;
                byState.merge(nz(rs.getString("state")), 1, Integer::sum);
                String svc = nz(rs.getString("service"));
                if (!svc.isEmpty()) {
                    byService.merge(svc, 1, Integer::sum);
                }
                if (rs.getInt("pushed") == 1) {
                    pushed++;
                }
            }
        } catch (Exception ex) {
            log.warn("汇总告警记录失败：{}", ex.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pushed", pushed);
        out.put("byState", byState);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(byService.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            top.add(Map.of("name", entries.get(i).getKey(), "count", entries.get(i).getValue()));
        }
        out.put("topServices", top);
        return out;
    }

    public boolean delete(long id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM alarm_runs WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除告警记录失败：{}", ex.getMessage());
            return false;
        }
    }

    public int clear() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            return st.executeUpdate("DELETE FROM alarm_runs");
        } catch (Exception ex) {
            log.warn("清空告警记录失败：{}", ex.getMessage());
            return 0;
        }
    }

    private static Record map(ResultSet rs) throws SQLException {
        return new Record(
                rs.getLong("id"),
                nz(rs.getString("received_at")),
                nz(rs.getString("source")),
                nz(rs.getString("alert_name")),
                nz(rs.getString("severity")),
                nz(rs.getString("service")),
                nz(rs.getString("trace_id")),
                nz(rs.getString("alarm_status")),
                nz(rs.getString("dedup_key")),
                nz(rs.getString("state")),
                nz(rs.getString("run_task_id")),
                nz(rs.getString("summary")),
                nz(rs.getString("output")),
                rs.getInt("pushed") == 1,
                rs.getLong("elapsed_ms"));
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
