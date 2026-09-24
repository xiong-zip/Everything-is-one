package com.agentflow.wecom;

import com.agentflow.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企微文档只读工具：搜索文档（按关键词）与读取文档内容（Markdown）。
 * 只读不落人工确认；搜索 limit 上限收到 10，避免整页企业文档灌进上下文。
 */
@Component
public class WecomDocQueryTool extends WecomToolSupport {

    private static final int MAX_LIMIT = 10;

    public WecomDocQueryTool(WecomCliRunner cli) {
        super(cli);
    }

    @Override
    public String name() {
        return "wecom.doc.query";
    }

    @Override
    public String description() {
        return "企业微信文档只读查询：搜索文档（mode=search，需 keywords，可选 limit）或读取文档内容（mode=read，需 docid 或文档 URL）。"
                + "需部署机 wecom-cli 已扫码授权（工作台 → 企业微信）";
    }

    @Override
    public String argsHint() {
        return "{\"mode\": \"search|read\", \"keywords\": \"关键词（search 必填，多个用空格分隔）\", "
                + "\"limit\": \"返回条数（默认 5，最大 10）\", \"docid\": \"文档 ID 或完整 URL（read 必填）\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, String userCommand) {
        ToolResult g = guard();
        if (g != null) {
            return g;
        }
        String mode = str(args.get("mode"));
        if (mode == null) {
            mode = str(args.get("keywords")) != null ? "search" : "read";
        }
        return switch (mode.toLowerCase()) {
            case "search" -> search(args);
            case "read" -> read(args);
            default -> ToolResult.note("wecom.doc.query 需要 mode=search 或 read");
        };
    }

    private ToolResult search(Map<String, Object> args) {
        List<String> keywords = keywords(args.get("keywords"));
        if (keywords.isEmpty()) {
            return ToolResult.note("wecom.doc.query 搜索需要 keywords 参数（一个或多个关键词）");
        }
        int limit = 5;
        String lim = str(args.get("limit"));
        if (lim != null) {
            try {
                limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(lim)));
            } catch (NumberFormatException ignored) {
            }
        }
        // 请求体经 stdin（--json -）传入：命令行传 JSON 的双引号会被 Java→Windows 转义拆碎
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keywords", keywords);
        body.put("limit", limit);
        return runJson(List.of("doc", "search"), body);
    }

    private ToolResult read(Map<String, Object> args) {
        String docid = str(args.get("docid"));
        if (docid == null) {
            docid = str(args.get("url"));
        }
        // docid 是占位符（规划器把参数提示当值传了）或没传但给了文档名：先按名搜索定位真实 docid
        if (isPlaceholderValue(docid)) {
            String docName = str(args.get("docName"));
            if (docName == null) {
                return ToolResult.note("读取文档需要真实 docid（文档 ID 或完整 URL）；不确定时先用 mode=search 按文档名搜索拿到 docid 再读");
            }
            String found = searchFirstDocid(docName);
            if (found == null) {
                return ToolResult.note("未搜到名为「" + docName + "」的文档，请确认名称后先用 mode=search 检索");
            }
            docid = found;
        }
        return runJson(List.of("doc", "contents", "get"), Map.of("docid", docid));
    }

    /** 按文档名搜索并取标题完全匹配的第一条 docid（找不到返回 null） */
    private String searchFirstDocid(String docName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keywords", keywords(docName));
        body.put("limit", 5);
        ToolResult r = runJson(List.of("doc", "search"), body);
        if (r == null || r.result() == null || !(r.result().get("docs") instanceof List<?> docs)) {
            return null;
        }
        for (Object o : docs) {
            if (o instanceof Map<?, ?> m) {
                String title = String.valueOf(m.get("doc_name"));
                if (title.equals(docName)) {
                    return String.valueOf(m.get("docid"));
                }
            }
        }
        Object first = docs.isEmpty() ? null : docs.get(0);
        return first instanceof Map<?, ?> m ? String.valueOf(m.get("docid")) : null;
    }

    /** keywords 接受字符串（空白分隔）或数组（纯函数，单测覆盖） */
    static List<String> keywords(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                String s = str(o);
                if (s != null) {
                    out.add(s);
                }
            }
        } else {
            String s = str(raw);
            if (s != null) {
                for (String p : s.split("[\\s,，]+")) {
                    if (!p.isBlank()) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }
}
