package com.agentflow.signoz;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路分析记录持久化（SQLite）：一次分析 = 一行，按 trace_id 去重。
 *
 * 与知识库（docs/incidents）的分工：
 * - 这里存"每次分析的原始记录"——谁在什么时候分析了哪条链路、耗时多少、指纹是什么、摘要全文；
 * - 知识库存"归纳后的故障模式"——同一模式只一条案例。
 * 两者互补：这个表可以回答"我一共分析过多少条链路、失败多少、哪些最常出问题"。
 *
 * 同一 trace 重复分析只更新计数与时间，不产生重复行（列表才可用）。
 * 持久化失败只记日志，不影响分析本身。
 */
@Component
public class TraceAnalysisStore {

    private static final Logger log = LoggerFactory.getLogger(TraceAnalysisStore.class);
    private static final String TS_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final String url;
    private final int maxRows;

    public TraceAnalysisStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path,
                              @Value("${agentflow.signoz.analysis-max:2000}") int maxRows) {
        String dbPath = StoragePaths.resolve(path);
        try {
            Path parent = Path.of(dbPath).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception ex) {
            log.warn("创建分析记录存储目录失败：{}", ex.getMessage());
        }
        this.url = "jdbc:sqlite:" + dbPath;
        this.maxRows = maxRows <= 0 ? 2000 : maxRows;
    }

    /** 一条分析记录 */
    public record AnalysisRecord(long id, String traceId, String analyzedAt, String timeRange,
                                 int spanCount, List<String> services, boolean found, boolean failed,
                                 String failurePoint, String errorClass, String signature,
                                 double totalMs, String env, String kbCaseId, String kbStrength,
                                 int analyzeCount, String digest) {
    }

    public record Stats(int total, int failed, int notFound, int withCase, int totalSpans,
                        List<Map<String, Object>> topServices,
                        List<Map<String, Object>> topSignatures) {
    }

    @PostConstruct
    void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS trace_analysis (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "trace_id TEXT NOT NULL," +
                    "analyzed_at TEXT NOT NULL," +
                    "time_range TEXT DEFAULT ''," +
                    "span_count INTEGER DEFAULT 0," +
                    "services TEXT DEFAULT ''," +
                    "found INTEGER DEFAULT 1," +
                    "failed INTEGER DEFAULT 0," +
                    "failure_point TEXT DEFAULT ''," +
                    "error_class TEXT DEFAULT ''," +
                    "signature TEXT DEFAULT ''," +
                    "total_ms REAL DEFAULT 0," +
                    "env TEXT DEFAULT ''," +
                    "kb_case_id TEXT DEFAULT ''," +
                    "kb_strength TEXT DEFAULT ''," +
                    "analyze_count INTEGER DEFAULT 1," +
                    "digest TEXT DEFAULT '')");
            // trace_id 唯一：同一条链路重复分析只更新，不产生重复行
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_trace_analysis_tid ON trace_analysis(trace_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_trace_analysis_at ON trace_analysis(analyzed_at DESC)");
            st.executeUpdate("DELETE FROM trace_analysis WHERE id NOT IN " +
                    "(SELECT id FROM trace_analysis ORDER BY id DESC LIMIT " + maxRows + ")");
        } catch (Exception ex) {
            log.error("初始化链路分析表失败，分析记录将不可用：{}", ex.getMessage());
        }
    }

    /**
     * 写入一次分析（按 trace_id 去重）：已存在则刷新字段并累加次数。
     * 返回记录 id；失败返回 -1（调用方据此提示"未能保存分析记录"）。
     */
    public long record(AnalysisRecord r) {
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern(TS_PATTERN));
        // 单语句 UPSERT：原来的「先查后插/改」在并发分析同一条链路时会撞唯一索引，
        // 异常被吞掉后返回 -1，那条分析记录就静默丢了
        String sql = "INSERT INTO trace_analysis(analyzed_at, time_range, span_count, services," +
                "found, failed, failure_point, error_class, signature, total_ms, env," +
                "kb_case_id, kb_strength, digest, trace_id, analyze_count)" +
                " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)" +
                " ON CONFLICT(trace_id) DO UPDATE SET" +
                " analyzed_at=excluded.analyzed_at, time_range=excluded.time_range," +
                " span_count=excluded.span_count, services=excluded.services," +
                " found=excluded.found, failed=excluded.failed," +
                " failure_point=excluded.failure_point, error_class=excluded.error_class," +
                " signature=excluded.signature, total_ms=excluded.total_ms, env=excluded.env," +
                " kb_case_id=excluded.kb_case_id, kb_strength=excluded.kb_strength," +
                " digest=excluded.digest, analyze_count=analyze_count+1";
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindBody(ps, r, now);
            ps.executeUpdate();
            Long id = findId(c, r.traceId());
            return id == null ? -1 : id;
        } catch (Exception ex) {
            log.warn("写入链路分析记录失败：{}", ex.getMessage());
            return -1;
        }
    }

    /** 公共字段绑定（UPDATE 与 INSERT 的字段顺序一致，共 15 个占位符） */
    private static void bindBody(PreparedStatement ps, AnalysisRecord r, String now) throws SQLException {
        ps.setString(1, now);
        ps.setString(2, nz(r.timeRange()));
        ps.setInt(3, r.spanCount());
        ps.setString(4, String.join(",", r.services() == null ? List.of() : r.services()));
        ps.setInt(5, r.found() ? 1 : 0);
        ps.setInt(6, r.failed() ? 1 : 0);
        ps.setString(7, nz(r.failurePoint()));
        ps.setString(8, nz(r.errorClass()));
        ps.setString(9, nz(r.signature()));
        ps.setDouble(10, r.totalMs());
        ps.setString(11, nz(r.env()));
        ps.setString(12, nz(r.kbCaseId()));
        ps.setString(13, nz(r.kbStrength()));
        ps.setString(14, nz(r.digest()));
        ps.setString(15, r.traceId());
    }

    private static Long findId(Connection c, String traceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM trace_analysis WHERE trace_id=?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** 列表：keyword 同时匹配 trace_id / 失败点 / 指纹 / 服务 */
    public List<AnalysisRecord> list(String keyword, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM trace_analysis");
        List<String> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" WHERE trace_id LIKE ? OR failure_point LIKE ? OR signature LIKE ?")
                    .append(" OR error_class LIKE ? OR services LIKE ? OR kb_case_id LIKE ?");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY analyzed_at DESC, id DESC LIMIT ? OFFSET ?");
        List<AnalysisRecord> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (String a : args) {
                ps.setString(i++, a);
            }
            ps.setInt(i++, Math.max(1, Math.min(limit, 500)));
            ps.setInt(i, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (Exception ex) {
            log.warn("读取链路分析记录失败：{}", ex.getMessage());
        }
        return out;
    }

    public int count(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM trace_analysis");
        List<String> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" WHERE trace_id LIKE ? OR failure_point LIKE ? OR signature LIKE ?")
                    .append(" OR error_class LIKE ? OR services LIKE ? OR kb_case_id LIKE ?");
            String like = "%" + keyword.trim() + "%";
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (String a : args) {
                ps.setString(i++, a);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception ex) {
            log.warn("统计链路分析记录失败：{}", ex.getMessage());
            return 0;
        }
    }

    /**
     * 同一故障指纹在历史上出现过几次（精确匹配，不走 LIKE——指纹里可能含 {@code _} 之类的通配符）。
     * 用于识别「修了又坏」的回归：次数大于 1 说明这个错误模式此前已经发生过。
     */
    public int countBySignature(String signature) {
        if (signature == null || signature.isBlank()) {
            return 0;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM trace_analysis WHERE signature = ?")) {
            ps.setString(1, signature);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception ex) {
            log.warn("按指纹统计失败：{}", ex.getMessage());
            return 0;
        }
    }

    /** 同一指纹的历史链路 ID（排除当前这条），最近优先，供回归提示写明「上次是哪条链路」 */
    public List<String> traceIdsBySignature(String signature, String excludeTraceId, int limit) {
        List<String> out = new ArrayList<>();
        if (signature == null || signature.isBlank()) {
            return out;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT trace_id FROM trace_analysis WHERE signature = ? AND trace_id <> ?" +
                        " ORDER BY analyzed_at DESC LIMIT ?")) {
            ps.setString(1, signature);
            ps.setString(2, excludeTraceId == null ? "" : excludeTraceId);
            ps.setInt(3, Math.max(1, Math.min(limit, 20)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(nz(rs.getString(1)));
                }
            }
        } catch (Exception ex) {
            log.warn("按指纹查询历史链路失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 找出某条链路对应的分析记录（告警排查结束后回查指纹用） */
    public AnalysisRecord findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM trace_analysis WHERE trace_id = ?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (Exception ex) {
            log.warn("按 trace ID 查询分析记录失败：{}", ex.getMessage());
            return null;
        }
    }

    /**
     * 时间窗内的分析记录（时间比较交给 SQLite，见 {@link #recentCount} 同款理由）。
     * 交班摘要、值班回顾这类「昨晚发生了什么」的问题都走这里。
     */
    public List<AnalysisRecord> listSince(int hours, boolean failedOnly, int limit) {
        int window = Math.max(1, Math.min(hours, 24 * 30));
        StringBuilder sql = new StringBuilder("SELECT * FROM trace_analysis WHERE analyzed_at >= datetime('now','localtime',?)");
        if (failedOnly) {
            sql.append(" AND failed = 1");
        }
        sql.append(" ORDER BY analyzed_at DESC, id DESC LIMIT ?");
        List<AnalysisRecord> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, "-" + window + " hours");
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        } catch (Exception ex) {
            log.warn("按时间窗读取分析记录失败：{}", ex.getMessage());
        }
        return out;
    }

    /**
     * 反复出现的故障指纹：同一指纹出现次数达到 minCount 即为「不是偶发」。
     * 这是「修了又坏」的判定依据——单看一次告警看不出回归，看指纹的重复次数才看得出来。
     */
    public List<Map<String, Object>> recurringSignatures(int minCount, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT signature, COUNT(*) AS n, MAX(analyzed_at) AS last_at, " +
                        "GROUP_CONCAT(DISTINCT failure_point) AS points " +
                        "FROM trace_analysis WHERE signature <> '' GROUP BY signature " +
                        "HAVING n >= ? ORDER BY n DESC, last_at DESC LIMIT ?")) {
            ps.setInt(1, Math.max(2, minCount));
            ps.setInt(2, Math.max(1, Math.min(limit, 50)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("signature", nz(rs.getString("signature")));
                    m.put("count", rs.getInt("n"));
                    m.put("lastAt", nz(rs.getString("last_at")));
                    m.put("failurePoints", nz(rs.getString("points")));
                    out.add(m);
                }
            }
        } catch (Exception ex) {
            log.warn("统计反复出现的指纹失败：{}", ex.getMessage());
        }
        return out;
    }

    public AnalysisRecord get(long id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM trace_analysis WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (Exception ex) {
            log.warn("读取链路分析记录失败：{}", ex.getMessage());
            return null;
        }
    }

    public boolean delete(long id) {
        try (Connection c = open(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM trace_analysis WHERE id=?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception ex) {
            log.warn("删除链路分析记录失败：{}", ex.getMessage());
            return false;
        }
    }

    public int clear() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            return st.executeUpdate("DELETE FROM trace_analysis");
        } catch (Exception ex) {
            log.warn("清空链路分析记录失败：{}", ex.getMessage());
            return 0;
        }
    }

    /** 汇总统计：失败数、命中案例数、Top 服务与 Top 故障指纹 */
    public Stats stats() {
        int total = 0;
        int failed = 0;
        int notFound = 0;
        int withCase = 0;
        int spans = 0;
        Map<String, Integer> byService = new LinkedHashMap<>();
        Map<String, Integer> bySignature = new LinkedHashMap<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM trace_analysis")) {
            while (rs.next()) {
                total++;
                if (rs.getInt("failed") == 1) {
                    failed++;
                }
                if (rs.getInt("found") == 0) {
                    notFound++;
                }
                if (!nz(rs.getString("kb_case_id")).isEmpty()) {
                    withCase++;
                }
                spans += rs.getInt("span_count");
                for (String s : split(rs.getString("services"))) {
                    byService.merge(s, 1, Integer::sum);
                }
                String sig = nz(rs.getString("signature"));
                if (!sig.isEmpty()) {
                    bySignature.merge(sig, 1, Integer::sum);
                }
            }
        } catch (Exception ex) {
            log.warn("汇总链路分析记录失败：{}", ex.getMessage());
        }
        return new Stats(total, failed, notFound, withCase, spans,
                topN(byService), topN(bySignature));
    }

    private static List<Map<String, Object>> topN(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(8, entries.size()); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", entries.get(i).getKey());
            m.put("count", entries.get(i).getValue());
            out.add(m);
        }
        return out;
    }

    private static AnalysisRecord map(ResultSet rs) throws SQLException {
        return new AnalysisRecord(
                rs.getLong("id"),
                nz(rs.getString("trace_id")),
                nz(rs.getString("analyzed_at")),
                nz(rs.getString("time_range")),
                rs.getInt("span_count"),
                split(rs.getString("services")),
                rs.getInt("found") == 1,
                rs.getInt("failed") == 1,
                nz(rs.getString("failure_point")),
                nz(rs.getString("error_class")),
                nz(rs.getString("signature")),
                rs.getDouble("total_ms"),
                nz(rs.getString("env")),
                nz(rs.getString("kb_case_id")),
                nz(rs.getString("kb_strength")),
                rs.getInt("analyze_count"),
                nz(rs.getString("digest")));
    }

    private Connection open() throws SQLException {
        return Sqlite.open(url);
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
