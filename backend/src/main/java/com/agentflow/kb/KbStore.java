package com.agentflow.kb;

import com.agentflow.engine.StoragePaths;
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
 * 个人知识库持久化（SQLite kb_files / kb_chunks 表）：
 * 上传的文件元数据 + 解析文本的分块。同名文件重传 = 整体替换（旧块先清空）。
 * 检索时 allChunks() 全量载入内存打分——个人规模（几百文件/几千块）毫秒级，无需倒排索引。
 */
@Component
public class KbStore {

    private static final Logger log = LoggerFactory.getLogger(KbStore.class);

    public record KbFile(long id, String filename, String storedName, long size,
                         int charCount, int chunkCount, String createdAt) {
    }

    public record KbChunk(long fileId, int seq, String content) {
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String url;

    public KbStore(@Value("${agentflow.storage.path:./data/agentflow.db}") String path) {
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
            st.execute("CREATE TABLE IF NOT EXISTS kb_files (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "filename TEXT NOT NULL UNIQUE," +
                    "stored_name TEXT NOT NULL," +
                    "size INTEGER NOT NULL," +
                    "char_count INTEGER NOT NULL," +
                    "chunk_count INTEGER NOT NULL," +
                    "created_at TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS kb_chunks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "file_id INTEGER NOT NULL," +
                    "seq INTEGER NOT NULL," +
                    "content TEXT NOT NULL)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_kb_chunks_file ON kb_chunks(file_id)");
        } catch (Exception ex) {
            log.error("初始化 kb 表失败：{}", ex.getMessage());
        }
    }

    /** 入库一个文件（同名替换）：写元数据并整体替换分块；返回文件 id */
    public long saveFile(String filename, String storedName, long size, int charCount, List<String> chunks) {
        String now = LocalDateTime.now().format(TS);
        try (Connection c = open()) {
            long fileId;
            try (PreparedStatement del = c.prepareStatement("DELETE FROM kb_files WHERE filename = ?");
                 PreparedStatement delChunks = c.prepareStatement("DELETE FROM kb_chunks WHERE file_id IN (SELECT id FROM kb_files WHERE filename = ?)")) {
                // 先删旧块再删旧文件（块的删除依赖 files 行还在）
                delChunks.setString(1, filename);
                delChunks.executeUpdate();
                del.setString(1, filename);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO kb_files(filename, stored_name, size, char_count, chunk_count, created_at) VALUES(?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, filename);
                ps.setString(2, storedName);
                ps.setLong(3, size);
                ps.setInt(4, charCount);
                ps.setInt(5, chunks.size());
                ps.setString(6, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    fileId = rs.next() ? rs.getLong(1) : -1;
                }
            }
            if (fileId > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO kb_chunks(file_id, seq, content) VALUES(?, ?, ?)")) {
                    for (int i = 0; i < chunks.size(); i++) {
                        ps.setLong(1, fileId);
                        ps.setInt(2, i + 1);
                        ps.setString(3, chunks.get(i));
                        ps.addBatch();
                        if (i % 200 == 199) {
                            ps.executeBatch();
                        }
                    }
                    ps.executeBatch();
                }
            }
            return fileId;
        } catch (Exception ex) {
            log.warn("保存知识库文件失败：{}", ex.getMessage());
            return -1;
        }
    }

    public List<KbFile> list() {
        List<KbFile> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, filename, stored_name, size, char_count, chunk_count, created_at FROM kb_files ORDER BY id DESC")) {
            while (rs.next()) {
                out.add(new KbFile(rs.getLong("id"), rs.getString("filename"), rs.getString("stored_name"),
                        rs.getLong("size"), rs.getInt("char_count"), rs.getInt("chunk_count"), rs.getString("created_at")));
            }
        } catch (Exception ex) {
            log.warn("读取知识库文件失败：{}", ex.getMessage());
        }
        return out;
    }

    public KbFile find(long id) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, filename, stored_name, size, char_count, chunk_count, created_at FROM kb_files WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new KbFile(rs.getLong("id"), rs.getString("filename"), rs.getString("stored_name"),
                        rs.getLong("size"), rs.getInt("char_count"), rs.getInt("chunk_count"), rs.getString("created_at")) : null;
            }
        } catch (Exception ex) {
            log.warn("查询知识库文件失败：{}", ex.getMessage());
            return null;
        }
    }

    public KbFile findByName(String filename) {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, filename, stored_name, size, char_count, chunk_count, created_at FROM kb_files WHERE filename = ?")) {
            ps.setString(1, filename);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new KbFile(rs.getLong("id"), rs.getString("filename"), rs.getString("stored_name"),
                        rs.getLong("size"), rs.getInt("char_count"), rs.getInt("chunk_count"), rs.getString("created_at")) : null;
            }
        } catch (Exception ex) {
            log.warn("按名查询知识库文件失败：{}", ex.getMessage());
            return null;
        }
    }

    /** 某文件的全部分块（按 seq 正序） */
    public List<KbChunk> chunksOf(long fileId) {
        List<KbChunk> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT seq, content FROM kb_chunks WHERE file_id = ? ORDER BY seq")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new KbChunk(fileId, rs.getInt("seq"), rs.getString("content")));
                }
            }
        } catch (Exception ex) {
            log.warn("读取知识库分块失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 全部分块（检索打分用，一次性载入） */
    public List<KbChunk> allChunks() {
        List<KbChunk> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT file_id, seq, content FROM kb_chunks ORDER BY file_id, seq")) {
            while (rs.next()) {
                out.add(new KbChunk(rs.getLong("file_id"), rs.getInt("seq"), rs.getString("content")));
            }
        } catch (Exception ex) {
            log.warn("读取全部知识库分块失败：{}", ex.getMessage());
        }
        return out;
    }

    /** 删除文件（级联删块）；返回被删的记录（供清理磁盘文件） */
    public KbFile delete(long id) {
        KbFile f = find(id);
        if (f == null) {
            return null;
        }
        try (Connection c = open();
             PreparedStatement delChunks = c.prepareStatement("DELETE FROM kb_chunks WHERE file_id = ?");
             PreparedStatement delFile = c.prepareStatement("DELETE FROM kb_files WHERE id = ?")) {
            delChunks.setLong(1, id);
            delChunks.executeUpdate();
            delFile.setLong(1, id);
            delFile.executeUpdate();
        } catch (Exception ex) {
            log.warn("删除知识库文件失败：{}", ex.getMessage());
            return null;
        }
        return f;
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }
}
