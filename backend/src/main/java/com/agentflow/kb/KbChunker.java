package com.agentflow.kb;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块（纯静态，可单测）：优先按空行/Markdown 标题行切段，
 * 段落聚合到目标块长（默认 600 字），相邻块保留 overlap（默认 80 字）重叠——
 * 跨块边界的句子在两块中各有一份，检索不会漏。
 * 超过单段上限的无空行长段落先硬切再聚合。
 */
public final class KbChunker {

    private KbChunker() {
    }

    /** 单段落硬切上限：无空行的超长段落（如 minified json）按此再切，避免一块占满全部上下文 */
    private static final int MAX_PARAGRAPH = 1200;

    public static List<String> chunk(String text, int targetChars, int overlap) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        int target = Math.max(100, targetChars);
        int ov = Math.max(0, Math.min(overlap, target / 2));

        String carry = ""; // 上一块结尾的重叠前缀
        StringBuilder buf = new StringBuilder();
        for (String p : splitParagraphs(text)) {
            if (buf.length() > 0 && carry.length() + buf.length() + p.length() + 1 > target) {
                emit(out, buf.toString(), target);
                carry = tail(buf.toString(), ov);
                buf.setLength(0);
            }
            if (buf.length() == 0 && !carry.isEmpty()) {
                buf.append(carry).append('\n');
            }
            buf.append(p).append('\n');
            if (buf.length() >= target) {
                emit(out, buf.toString(), target);
                carry = tail(buf.toString(), ov);
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            emit(out, buf.toString(), target);
        }
        return out;
    }

    /** 落一块：去首尾空白；超长时切分成多块而不是截断丢弃（任何输入都不允许丢内容） */
    private static void emit(List<String> out, String block, int target) {
        String b = block.strip();
        int cap = target * 3 / 2;
        while (!b.isEmpty()) {
            if (b.length() <= cap) {
                out.add(b);
                return;
            }
            out.add(b.substring(0, cap));
            b = b.substring(cap).strip();
        }
    }

    /** 块结尾 overlap 字符（按字符边界，中文安全） */
    private static String tail(String block, int ov) {
        if (ov <= 0) {
            return "";
        }
        String b = block.strip();
        return b.length() <= ov ? b : b.substring(b.length() - ov);
    }

    /** 段落切分：空行分界；Markdown 标题行（# 开头）自成边界且归入后段（命中标题即可定位整节） */
    private static List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.stripTrailing();
            boolean isHeading = line.startsWith("#");
            if (line.isBlank() || isHeading) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString().strip());
                    cur.setLength(0);
                }
                if (isHeading) {
                    cur.append(line).append('\n');
                }
                continue;
            }
            cur.append(line).append('\n');
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString().strip());
        }
        List<String> normalized = new ArrayList<>();
        for (String p : out) {
            while (p.length() > MAX_PARAGRAPH) {
                normalized.add(p.substring(0, MAX_PARAGRAPH));
                p = p.substring(MAX_PARAGRAPH);
            }
            if (!p.isBlank()) {
                normalized.add(p);
            }
        }
        return normalized;
    }
}
