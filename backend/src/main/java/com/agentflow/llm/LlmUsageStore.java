package com.agentflow.llm;

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
 * LLM 调用埋点（SQLite llm_usage 表，与任务历史同库）：每次调用一行。
 *
 * <p>这个项目每天都在分析别人的链路，却一直不知道自己哪一步最慢、哪个环节最贵。
 * 有了这张表，「本次任务花了多少 token」「过去 24 小时哪个阶段是大头」才是可回答的问题，
 * 后续任何成本或延迟优化也才有依据。
 *
 * <p>两个口径上的诚实处理：
 * <ul>
 *   <li><b>estimated 标记</b>——非流式响应里服务端会带精确 usage，直接记；
 *       流式响应默认不返回 usage（除非显式请求），此时按字符数估算并置 estimated=1。
 *       估算值参与统计但不冒充精确值，前端会标注出来；</li>
 *   <li><b>失败也记一行</b>——调用失败同样消耗时间与可能的额度，且失败率本身就是要看的指标，
 *       只记成功会让延迟与成功率统计双双失真。</li>
 * </ul>
 */
@Component
public class LlmUsageStore {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;
    private final int maxRows;

    public LlmUsageStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path,
                         @Value("${agentflow.llm.usage-max:20000}") int maxRows) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建 LLM 埋点存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
        this.maxRows = maxRows <= 0 ? 20000 : maxRows;
    }

    /**
     * 一次 LLM 调用。
     *
     * @param purpose    用途（plan/react/reason/generate/summarize/memory/test/chat），用于按阶段归因
     * @param estimated  token 数是否为估算值（流式响应拿不到精确 usage 时为 true）
     * @param taskId     所属任务，空串表示不属于任何任务（如连通性测试）
     */
    public record LlmCall(String purpose, String provider, String model, int promptTokens,
                          int completionTokens, int totalTokens, boolean estimated, long elapsedMs,
                          boolean ok, String error, boolean stream, String taskId) {
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS llm_usage (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "created_at TEXT NOT NULL," +
                    "purpose TEXT DEFAULT ''," +
                    "provider TEXT DEFAULT ''," +
                    "model TEXT DEFAULT ''," +
                    "prompt_tokens INTEGER DEFAULT 0," +
                    "completion_tokens INTEGER DEFAULT 0," +
                    "total_tokens INTEGER DEFAULT 0," +
                    "estimated INTEGER DEFAULT 0," +
                    "elapsed_ms INTEGER DEFAULT 0," +
                    "ok INTEGER DEFAULT 1," +
                    "error TEXT DEFAULT ''," +
                    "stream INTEGER DEFAULT 0," +
                    "task_id TEXT DEFAULT '')");
            st.execute("CREATE INDEX IF NOT EXISTS idx_llm_usage_at ON llm_usage(created_at DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_llm_usage_task ON llm_usage(task_id)");
            st.executeUpdate("DELETE FROM llm_usage WHERE id NOT IN " +
                    "(SELECT id FROM llm_usage ORDER BY id DESC LIMIT " + maxRows + ")");
        } catch (Exception ex) {
            log.error("初始化 llm_usage 表失败，LLM 埋点将不可用：{}", ex.getMessage());
        }
    }

    /** 落一次调用。失败只记日志——埋点绝不能反过来把 LLM 调用打挂 */
    public void record(LlmCall call) {
        if (call == null) {
            return;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO llm_usage(created_at, purpose, provider, model, prompt_tokens, completion_tokens," +
                        " total_tokens, estimated, elapsed_ms, ok, error, stream, task_id)" +
                        " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, LocalDateTime.now().format(TS));
            ps.setString(2, nz(call.purpose()));
            ps.setString(3, nz(call.provider()));
            ps.setString(4, nz(call.model()));
            ps.setInt(5, Math.max(0, call.promptTokens()));
            ps.setInt(6, Math.max(0, call.completionTokens()));
            ps.setInt(7, Math.max(0, call.totalTokens()));
            ps.setInt(8, call.estimated() ? 1 : 0);
            ps.setLong(9, Math.max(0, call.elapsedMs()));
            ps.setInt(10, call.ok() ? 1 : 0);
            ps.setString(11, truncate(call.error(), 300));
            ps.setInt(12, call.stream() ? 1 : 0);
            ps.setString(13, nz(call.taskId()));
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("写入 LLM 埋点失败：{}", ex.getMessage());
        }
    }

    /** 时间窗内的总体指标 */
    public Map<String, Object> totals(int hours) {
        Map<String, Object> out = new LinkedHashMap<>();
        String since = "-" + Math.max(1, Math.min(hours, 24 * 90)) + " hours";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens," +
                        " COALESCE(SUM(prompt_tokens),0) AS prompt_tokens," +
                        " COALESCE(SUM(completion_tokens),0) AS completion_tokens," +
                        " COALESCE(AVG(elapsed_ms),0) AS avg_ms, COALESCE(MAX(elapsed_ms),0) AS max_ms," +
                        " COALESCE(SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END),0) AS failures," +
                        " COALESCE(SUM(CASE WHEN estimated = 1 THEN 1 ELSE 0 END),0) AS estimated_calls," +
                        " COALESCE(SUM(elapsed_ms),0) AS total_ms " +
                        "FROM llm_usage WHERE created_at >= datetime('now','localtime',?)")) {
            ps.setString(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    out.put("calls", rs.getInt("calls"));
                    out.put("tokens", rs.getLong("tokens"));
                    out.put("promptTokens", rs.getLong("prompt_tokens"));
                    out.put("completionTokens", rs.getLong("completion_tokens"));
                    out.put("avgMs", Math.round(rs.getDouble("avg_ms")));
                    out.put("maxMs", rs.getLong("max_ms"));
                    out.put("totalMs", rs.getLong("total_ms"));
                    out.put("failures", rs.getInt("failures"));
                    out.put("estimatedCalls", rs.getInt("estimated_calls"));
                }
            }
        } catch (Exception ex) {
            log.warn("汇总 LLM 埋点失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 按用途（阶段）分组：回答「钱和时间花在哪个环节」 */
    public List<Map<String, Object>> byPurpose(int hours) {
        return groupBy(hours, "purpose");
    }

    /** 按模型分组：回答「换模型前后各花了多少」 */
    public List<Map<String, Object>> byModel(int hours) {
        return groupBy(hours, "model");
    }

    private List<Map<String, Object>> groupBy(int hours, String column) {
        List<Map<String, Object>> out = new ArrayList<>();
        String since = "-" + Math.max(1, Math.min(hours, 24 * 90)) + " hours";
        String sql = "SELECT " + column + " AS k, COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens," +
                " COALESCE(AVG(elapsed_ms),0) AS avg_ms, COALESCE(SUM(elapsed_ms),0) AS total_ms," +
                " COALESCE(SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END),0) AS failures" +
                " FROM llm_usage WHERE created_at >= datetime('now','localtime',?)" +
                " GROUP BY " + column + " ORDER BY tokens DESC, calls DESC";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", nz(rs.getString("k")));
                    m.put("calls", rs.getInt("calls"));
                    m.put("tokens", rs.getLong("tokens"));
                    m.put("avgMs", Math.round(rs.getDouble("avg_ms")));
                    m.put("totalMs", rs.getLong("total_ms"));
                    m.put("failures", rs.getInt("failures"));
                    out.add(m);
                }
            }
        } catch (Exception ex) {
            log.warn("按 {} 汇总 LLM 埋点失败：{}", column, ex.getMessage());
        }
        return out;
    }

    /** 按小时的时间序列（补零，便于前端直接画图） */
    public List<Map<String, Object>> hourly(int hours) {
        int window = Math.max(1, Math.min(hours, 24 * 30));
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        LocalDateTime cursor = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .minusHours(window - 1L);
        for (int i = 0; i < window; i++) {
            String key = cursor.format(DateTimeFormatter.ofPattern("MM-dd HH"));
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("hour", key);
            bucket.put("calls", 0);
            bucket.put("tokens", 0L);
            bucket.put("failures", 0);
            buckets.put(key, bucket);
            cursor = cursor.plusHours(1);
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT strftime('%m-%d %H', created_at) AS h, COUNT(*) AS calls," +
                        " COALESCE(SUM(total_tokens),0) AS tokens," +
                        " COALESCE(SUM(CASE WHEN ok = 0 THEN 1 ELSE 0 END),0) AS failures" +
                        " FROM llm_usage WHERE created_at >= datetime('now','localtime',?) GROUP BY h")) {
            ps.setString(1, "-" + window + " hours");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> bucket = buckets.get(nz(rs.getString("h")));
                    if (bucket != null) {
                        bucket.put("calls", rs.getInt("calls"));
                        bucket.put("tokens", rs.getLong("tokens"));
                        bucket.put("failures", rs.getInt("failures"));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("读取 LLM 埋点时间序列失败：{}", ex.getMessage());
        }
        return new ArrayList<>(buckets.values());
    }

    /** 单次任务的花费排行：定位「哪个定时任务最贵」 */
    public List<Map<String, Object>> topTasks(int hours, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT task_id, COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens," +
                        " COALESCE(SUM(elapsed_ms),0) AS total_ms FROM llm_usage" +
                        " WHERE task_id <> '' AND created_at >= datetime('now','localtime',?)" +
                        " GROUP BY task_id ORDER BY tokens DESC LIMIT ?")) {
            ps.setString(1, "-" + Math.max(1, Math.min(hours, 24 * 90)) + " hours");
            ps.setInt(2, Math.max(1, Math.min(limit, 50)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskId", nz(rs.getString("task_id")));
                    m.put("calls", rs.getInt("calls"));
                    m.put("tokens", rs.getLong("tokens"));
                    m.put("totalMs", rs.getLong("total_ms"));
                    out.add(m);
                }
            }
        } catch (Exception ex) {
            log.warn("统计任务花费失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 某次任务的分阶段明细 */
    public List<Map<String, Object>> taskBreakdown(String taskId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (taskId == null || taskId.isBlank()) {
            return out;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT purpose, COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens," +
                        " COALESCE(SUM(elapsed_ms),0) AS total_ms FROM llm_usage WHERE task_id = ?" +
                        " GROUP BY purpose ORDER BY total_ms DESC")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("purpose", nz(rs.getString("purpose")));
                    m.put("calls", rs.getInt("calls"));
                    m.put("tokens", rs.getLong("tokens"));
                    m.put("totalMs", rs.getLong("total_ms"));
                    out.add(m);
                }
            }
        } catch (Exception ex) {
            log.warn("读取任务分阶段明细失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 最近的调用流水，便于核对异常 */
    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM llm_usage ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 200)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("createdAt", nz(rs.getString("created_at")));
                    m.put("purpose", nz(rs.getString("purpose")));
                    m.put("model", nz(rs.getString("model")));
                    m.put("provider", nz(rs.getString("provider")));
                    m.put("promptTokens", rs.getInt("prompt_tokens"));
                    m.put("completionTokens", rs.getInt("completion_tokens"));
                    m.put("totalTokens", rs.getInt("total_tokens"));
                    m.put("estimated", rs.getInt("estimated") == 1);
                    m.put("elapsedMs", rs.getLong("elapsed_ms"));
                    m.put("ok", rs.getInt("ok") == 1);
                    m.put("stream", rs.getInt("stream") == 1);
                    m.put("error", nz(rs.getString("error")));
                    m.put("taskId", nz(rs.getString("task_id")));
                    out.add(m);
                }
            }
        } catch (Exception ex) {
            log.warn("读取 LLM 调用流水失败：{}", ex.getMessage());
        }
        return out;
    }

    public int count() {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM llm_usage")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    public int clear() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            return st.executeUpdate("DELETE FROM llm_usage");
        } catch (Exception ex) {
            log.warn("清空 LLM 埋点失败：{}", ex.getMessage());
            return 0;
        }
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? nz(s) : s.substring(0, max) + "…";
    }
}
