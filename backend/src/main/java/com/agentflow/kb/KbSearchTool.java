package com.agentflow.kb;

import com.agentflow.tool.Tool;
import com.agentflow.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人知识库检索工具（kb.query，只读）：三模式——
 * search（默认，关键词打分取 Top-K 分块，供基于知识库作答）、list（文件清单）、read（读某文件全部分块）。
 *
 * 无 embedding API（DeepSeek 仅对话接口），检索用内存关键词打分：
 * 查询切词（中文 2-gram + 西文小写词），块得分 = Σ 词频 × log(1 + 总块数/含词块数)（罕见词权重高）。
 * 个人规模（几千块）全量打分毫秒级，无需倒排索引。
 */
@Component
public class KbSearchTool implements Tool {

    /** 中文停用词：只出现在停用词里的查询不给分（如「的知识库」） */
    private static final Set<String> STOPWORDS = Set.of(
            "什么", "怎么", "怎样", "如何", "为什么", "请问", "告诉", "一下", "关于", "对于",
            "这个", "那个", "哪些", "有没有", "知识", "知识库", "资料", "文档", "文件",
            "the", "a", "an", "of", "to", "and", "is", "in", "it", "for", "on", "with", "how", "what", "why");

    private static final Pattern LATIN_WORD = Pattern.compile("[a-z0-9][a-z0-9_.-]{1,}");

    private final KbStore store;
    private final int chunkChars;
    private final int chunkOverlap;
    private final int topK;

    public KbSearchTool(KbStore store,
                        @Value("${agentflow.kb.chunk-chars:600}") int chunkChars,
                        @Value("${agentflow.kb.chunk-overlap:80}") int chunkOverlap,
                        @Value("${agentflow.kb.top-k:5}") int topK) {
        this.store = store;
        this.chunkChars = Math.max(100, chunkChars);
        this.chunkOverlap = Math.max(0, chunkOverlap);
        this.topK = Math.max(1, topK);
    }

    /** 知识库是否为空（引擎启发式规划判断是否加检索步用） */
    public boolean isEmpty() {
        return store.list().isEmpty();
    }

    @Override
    public String name() {
        return "kb.query";
    }

    @Override
    public String description() {
        List<KbStore.KbFile> files = store.list();
        if (files.isEmpty()) {
            return "个人知识库检索（当前为空：请提示用户点击输入框旁 📎 或工作台 → 知识库 上传文档）";
        }
        String names = files.stream().limit(8).map(KbStore.KbFile::filename)
                .reduce((a, b) -> a + "、" + b).orElse("");
        return "个人知识库检索：用户提到「知识库/资料/文档/规范」或询问已上传文档内容时用本工具"
                + "（mode=search 默认，传完整问题作 query；mode=list 看文件清单；mode=read 读取某文件全文）。"
                + "基于知识库作答时必须先检索再回答，答案要注明来源文件。知识库现有 "
                + files.size() + " 个文件：" + names + (files.size() > 8 ? " 等" : "");
    }

    @Override
    public String argsHint() {
        return "{\"mode\": \"search|list|read（默认 search）\", \"query\": \"检索问题（search 时必填，用完整自然语言问题）\", "
                + "\"file\": \"文件名（read 时必填）\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        String mode = str(args.get("mode"));
        if (mode == null) {
            mode = userCommand != null && (userCommand.contains("有哪些文件") || userCommand.contains("文件清单")
                    || userCommand.contains("知识库里有什么")) ? "list" : "search";
        }
        try {
            return switch (mode.toLowerCase(Locale.ROOT)) {
                case "list" -> listFiles();
                case "read" -> readFile(str(args.get("file")));
                default -> search(str(args.get("query")), userCommand);
            };
        } catch (Exception ex) {
            return ToolResult.note("知识库查询失败：" + ex.getMessage());
        }
    }

    /* ---------- 三模式 ---------- */

    private ToolResult listFiles() {
        List<KbStore.KbFile> files = store.list();
        if (files.isEmpty()) {
            return ToolResult.note("知识库为空：点击输入框旁 📎 或工作台 → 知识库 上传文档（支持 md/txt/csv/json/log/html/pdf/docx/xlsx）");
        }
        List<String> lines = new ArrayList<>();
        for (KbStore.KbFile f : files) {
            lines.add(f.filename() + " · " + f.charCount() + " 字 · " + f.chunkCount() + " 块 · 上传于 " + f.createdAt());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileCount", files.size());
        return new ToolResult("kb", result, lines, "知识库共 " + files.size() + " 个文件");
    }

    private ToolResult readFile(String filename) {
        if (filename == null) {
            return ToolResult.note("read 模式需要 file 参数（文件名，可先用 mode=list 查看）");
        }
        KbStore.KbFile f = store.findByName(filename);
        if (f == null) {
            // 模糊兜底：子串匹配唯一命中即用
            List<KbStore.KbFile> hits = store.list().stream()
                    .filter(x -> x.filename().toLowerCase(Locale.ROOT).contains(filename.toLowerCase(Locale.ROOT))).toList();
            if (hits.size() == 1) {
                f = hits.get(0);
            } else {
                return ToolResult.note("知识库里没有「" + filename + "」"
                        + (hits.isEmpty() ? "" : "（有相近文件：" + hits.get(0).filename() + "）") + "，可用 mode=list 查看全部文件");
            }
        }
        List<KbStore.KbChunk> chunks = store.chunksOf(f.id());
        List<String> lines = new ArrayList<>();
        lines.add("【" + f.filename() + "】共 " + f.charCount() + " 字 / " + chunks.size() + " 块");
        for (KbStore.KbChunk c : chunks) {
            lines.add("[块 " + c.seq() + "] " + c.content());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", f.filename());
        result.put("charCount", f.charCount());
        result.put("chunkCount", chunks.size());
        result.put("chunks", chunks.stream().map(c -> Map.of("seq", c.seq(), "content", c.content())).toList());
        return new ToolResult("kb", result, lines,
                "已读取知识库文件 " + f.filename() + "（" + f.charCount() + " 字，" + chunks.size() + " 块）");
    }

    private ToolResult search(String query, String userCommand) {
        List<KbStore.KbFile> files = store.list();
        if (files.isEmpty()) {
            return ToolResult.note("知识库为空：请先上传文档（输入框 📎 或工作台 → 知识库），再基于知识库提问");
        }
        String q = query != null ? query : userCommand;
        if (q == null || q.isBlank()) {
            return ToolResult.note("search 模式需要 query 参数（完整自然语言问题）");
        }
        List<KbStore.KbChunk> all = store.allChunks();
        List<String> tokens = tokenize(q);
        if (tokens.isEmpty()) {
            return ToolResult.note("检索词全是停用词，换个更具体的问题试试");
        }

        // 逆文档频率：含某词的块数越少权重越高
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Integer>> tfs = new ArrayList<>();
        for (KbStore.KbChunk c : all) {
            Map<String, Integer> tf = termFreq(c.content(), tokens);
            tfs.add(tf);
            for (String t : tf.keySet()) {
                df.merge(t, 1, Integer::sum);
            }
        }
        record Hit(KbStore.KbChunk chunk, double score) {
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (tfs.get(i).isEmpty()) {
                continue;
            }
            double score = 0;
            for (Map.Entry<String, Integer> e : tfs.get(i).entrySet()) {
                double idf = Math.log(1.0 + (double) all.size() / df.get(e.getKey()));
                score += e.getValue() * idf;
            }
            hits.add(new Hit(all.get(i), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());

        Map<Long, String> fileNames = new HashMap<>();
        for (KbStore.KbFile f : files) {
            fileNames.put(f.id(), f.filename());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", q);
        result.put("matchCount", hits.size());
        List<Map<String, Object>> hitList = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        lines.add("检索「" + truncateZh(q, 40) + "」· 命中 " + hits.size() + " 块，取前 " + Math.min(topK, hits.size()) + " 块：");
        for (Hit h : hits.subList(0, Math.min(topK, hits.size()))) {
            String fn = fileNames.getOrDefault(h.chunk().fileId(), "文件#" + h.chunk().fileId());
            String excerpt = truncateZh(h.chunk().content(), 300);
            hitList.add(Map.of("file", fn, "seq", h.chunk().seq(),
                    "score", Math.round(h.score() * 10) / 10.0, "excerpt", excerpt));
            lines.add("【" + fn + " · 块" + h.chunk().seq() + " · " + Math.round(h.score() * 10) / 10.0 + "分】" + excerpt);
        }
        result.put("hits", hitList);
        String summary = hits.isEmpty()
                ? "知识库检索无命中（" + files.size() + " 个文件）"
                : "知识库命中 " + hits.size() + " 块 · 来源 " + hitList.stream()
                        .map(x -> String.valueOf(x.get("file"))).distinct().count() + " 个文件，最相关："
                        + hitList.get(0).get("file");
        return new ToolResult("kb", result, lines, summary);
    }

    /* ---------- 纯函数（单测覆盖） ---------- */

    /**
     * 查询切词：西文小写整词（≥2 字符）+ 中文滑出 2-gram；
     * 纯停用词结果被丢弃（「知识库」这类词本身不参与打分，否则所有块都命中）。
     */
    static List<String> tokenize(String query) {
        List<String> out = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return out;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        Matcher m = LATIN_WORD.matcher(lower);
        StringBuilder noLatin = new StringBuilder();
        int copied = 0;
        while (m.find()) {
            String w = m.group();
            if (!STOPWORDS.contains(w)) {
                out.add(w);
            }
            noLatin.append(lower, copied, m.start());
            copied = m.end();
        }
        noLatin.append(lower, copied, lower.length());
        // 中文段滑 2-gram
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < noLatin.length(); i++) {
            char ch = noLatin.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                cjk.append(ch);
                if (cjk.length() == 2) {
                    String g = cjk.toString();
                    if (!STOPWORDS.contains(g)) {
                        out.add(g);
                    }
                    cjk.deleteCharAt(0);
                }
            } else {
                cjk.setLength(0);
            }
        }
        return out;
    }

    /** 块内词频：只统计查询词在块内容中的出现次数（子串计数，中文 2-gram 天然可子串匹配） */
    static Map<String, Integer> termFreq(String content, List<String> tokens) {
        Map<String, Integer> out = new HashMap<>();
        if (content == null || content.isEmpty()) {
            return out;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String t : new HashSet<>(tokens)) {
            int count = 0;
            int idx = 0;
            while ((idx = lower.indexOf(t, idx)) >= 0) {
                count++;
                idx += t.length();
            }
            if (count > 0) {
                out.put(t, count);
            }
        }
        return out;
    }

    private static String truncateZh(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
