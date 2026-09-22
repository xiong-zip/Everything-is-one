package com.agentflow.controller;

import com.agentflow.engine.StoragePaths;
import com.agentflow.kb.KbChunker;
import com.agentflow.kb.KbSearchTool;
import com.agentflow.kb.KbStore;
import com.agentflow.kb.KbTextExtractor;
import com.agentflow.kb.KbVectorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 个人知识库：上传（解析→分块→索引）、文件管理、检索预览。
 * 上传文件落 ./data/kb-files（重名加时间戳防覆盖），文本进 SQLite；同名重传=整体替换。
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbStore store;
    private final KbTextExtractor extractor;
    private final KbSearchTool searchTool;
    private final KbVectorService vectorService;
    private final Path filesDir;
    private final int maxFiles;
    private final int chunkChars;
    private final int chunkOverlap;

    public KbController(KbStore store, KbTextExtractor extractor, KbSearchTool searchTool,
                        KbVectorService vectorService,
                        @Value("${agentflow.kb.files-path:./data/kb-files}") String filesPath,
                        @Value("${agentflow.kb.max-files:200}") int maxFiles,
                        @Value("${agentflow.kb.chunk-chars:600}") int chunkChars,
                        @Value("${agentflow.kb.chunk-overlap:80}") int chunkOverlap) {
        this.store = store;
        this.extractor = extractor;
        this.searchTool = searchTool;
        this.vectorService = vectorService;
        this.filesDir = Path.of(StoragePaths.resolve(filesPath));
        this.maxFiles = maxFiles;
        this.chunkChars = chunkChars;
        this.chunkOverlap = chunkOverlap;
        try {
            Files.createDirectories(this.filesDir);
        } catch (Exception ignored) {
            // 建目录失败会在上传时暴露
        }
    }

    /** 上传并索引（multipart）。同名文件重传 = 替换（旧分块清空重建） */
    @PostMapping("/files")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }
        filename = Path.of(filename).getFileName().toString(); // 防路径穿越
        if (!KbTextExtractor.supported(filename)) {
            throw new IllegalArgumentException("暂不支持 " + filename
                    + "。支持：md/txt/csv/json/log/html/pdf/docx/xlsx（旧版 doc/xls 请先另存为新格式）");
        }
        boolean replace = store.findByName(filename) != null;
        if (!replace && store.list().size() >= maxFiles) {
            throw new IllegalArgumentException("知识库文件数已达上限 " + maxFiles + "，请先删除部分文件");
        }
        try {
            byte[] bytes = file.getBytes();
            String text = extractor.extract(filename, bytes);
            if (text.isBlank()) {
                throw new IllegalArgumentException(filename + " 没有解析出任何文本（扫描版 PDF/空文档无法入库）");
            }
            List<String> chunks = KbChunker.chunk(text, chunkChars, chunkOverlap);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException(filename + " 解析文本过短，无法分块入库");
            }
            // 落盘原始文件（重名加时间戳，避免覆盖历史版本）
            String storedName = replace ? store.findByName(filename).storedName()
                    : System.currentTimeMillis() + "-" + filename.replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = filesDir.resolve(storedName);
            Files.createDirectories(filesDir);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

            long id = store.saveFile(filename, storedName, file.getSize(), text.length(), chunks);
            if (id <= 0) {
                throw new IllegalArgumentException("入库失败，请重试");
            }
            // 向量索引异步补齐：上传主流程不等它，失败可由「重建索引」兜底
            if (vectorService.active()) {
                final long fileId = id;
                java.util.concurrent.CompletableFuture.runAsync(() -> vectorService.indexFile(fileId));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", "saved");
            out.put("id", id);
            out.put("filename", filename);
            out.put("charCount", text.length());
            out.put("chunkCount", chunks.size());
            out.put("replaced", replace);
            return out;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("上传失败：" + ex.getMessage());
        }
    }

    @GetMapping("/files")
    public Map<String, Object> list() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (KbStore.KbFile f : store.list()) {
            items.add(fileView(f, false));
        }
        out.put("items", items);
        return out;
    }

    /** 文件详情：含全部分块（面板阅读全文用） */
    @GetMapping("/files/{id}")
    public Map<String, Object> detail(@PathVariable("id") long id) {
        KbStore.KbFile f = store.find(id);
        if (f == null) {
            throw new IllegalArgumentException("文件不存在或已删除");
        }
        Map<String, Object> out = fileView(f, true);
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (KbStore.KbChunk c : store.chunksOf(f.id())) {
            chunks.add(Map.of("seq", c.seq(), "content", c.content()));
        }
        out.put("chunks", chunks);
        return out;
    }

    @DeleteMapping("/files/{id}")
    public Map<String, String> delete(@PathVariable("id") long id) {
        KbStore.KbFile f = store.delete(id);
        if (f == null) {
            throw new IllegalArgumentException("文件不存在或已删除");
        }
        try {
            Files.deleteIfExists(filesDir.resolve(f.storedName()));
        } catch (Exception ignored) {
            // 磁盘文件残留不阻断删除
        }
        return Map.of("ok", "deleted", "filename", f.filename());
    }

    /** 检索预览（面板试搜用；与 kb.query 工具同一套打分逻辑） */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String q) {
        if (q == null || q.isBlank()) {
            return Map.of("hits", List.of());
        }
        com.agentflow.tool.ToolResult tr = searchTool.execute(Map.of("mode", "search", "query", q), q);
        Map<String, Object> out = new LinkedHashMap<>();
        if (tr.result() != null) {
            out.putAll(tr.result());
        } else {
            out.put("note", tr.summary());
        }
        return out;
    }

    private static Map<String, Object> fileView(KbStore.KbFile f, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("filename", f.filename());
        m.put("size", f.size());
        m.put("sizeText", humanSize(f.size()));
        m.put("charCount", f.charCount());
        m.put("chunkCount", f.chunkCount());
        m.put("createdAt", f.createdAt());
        return m;
    }

    /** 向量检索状态：是否启用、模型、已索引块数（面板提示与「重建索引」按钮依据） */
    @GetMapping("/vector")
    public Map<String, Object> vectorStatus() {
        return vectorService.status();
    }

    /** 重建向量索引：补齐缺失向量；换过嵌入模型时先清空旧向量再重算 */
    @PostMapping("/vector/rebuild")
    public Map<String, Object> rebuildVector() {
        return vectorService.backfill();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
