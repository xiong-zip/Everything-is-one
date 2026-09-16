package com.agentflow.kb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文本分块：段落边界、块长上限、重叠与中文 */
class KbChunkerTest {

    @Test
    void shortTextIsSingleChunk() {
        List<String> chunks = KbChunker.chunk("只有一句话的短文档", 600, 80);
        assertEquals(1, chunks.size());
        assertEquals("只有一句话的短文档", chunks.get(0));
    }

    @Test
    void splitsOnBlankLinesAndHeadings() {
        String text = """
                # 第一节 概述
                这是概述内容，讲整体背景与目标。

                # 第二节 用法
                这里讲具体怎么用，步骤一二三。
                """;
        List<String> chunks = KbChunker.chunk(text, 600, 80);
        // 目标 600 字下两节会聚成一块或两块；断言标题随正文保留（检索按标题定位）
        String all = String.join("\n", chunks);
        assertTrue(all.contains("第一节 概述"));
        assertTrue(all.contains("第二节 用法"));
    }

    @Test
    void longParagraphsAggregateToTargetSize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("第").append(i).append("段：这是一段中等长度的中文内容，用来测试聚合逻辑。");
            sb.append("\n\n");
        }
        List<String> chunks = KbChunker.chunk(sb.toString(), 600, 80);
        assertTrue(chunks.size() >= 2, "约 1500 字应分成多块，实际 " + chunks.size());
        for (String c : chunks) {
            // 块长不超过 1.5 倍目标
            assertTrue(c.length() <= 900, "块长 " + c.length() + " 超上限");
        }
        // 相邻块有重叠（下一块开头包含上一块结尾的片段）
        if (chunks.size() >= 2) {
            String prevTail = chunks.get(0).substring(Math.max(0, chunks.get(0).length() - 80));
            boolean overlapped = false;
            for (int i = 1; i < chunks.size(); i++) {
                if (chunks.get(i).contains(prevTail.substring(Math.max(0, prevTail.length() - 20)))) {
                    overlapped = true;
                    break;
                }
            }
            assertTrue(overlapped, "相邻块应保留重叠内容");
        }
    }

    @Test
    void giantSingleParagraphIsHardSplit() {
        String giant = "无空行的超长文本。".repeat(300); // ~3300 字无换行
        List<String> chunks = KbChunker.chunk(giant, 600, 80);
        assertTrue(chunks.size() >= 2);
    }

    @Test
    void noContentEverDropped() {
        // 回归：任何输入分块后，全部非空白内容都必须保留且保序
        //（分块器合法地把段间空行规范化为单换行，所以两侧先去空白再比对子序列）
        for (String text : new String[]{
                "超长段落".repeat(400),                          // 单段超长（>1.5×目标，触发 emit 切分）
                "段落内容甲。\n\n段落内容乙。".repeat(80),          // 多段落 + 空行
                "# 标题\n正文".repeat(100)                        // 密集标题
        }) {
            List<String> chunks = KbChunker.chunk(text, 600, 80);
            String textNorm = text.replaceAll("\\s+", "");
            String joinedNorm = String.join("", chunks).replaceAll("\\s+", "");
            assertTrue(isSubsequence(textNorm, joinedNorm),
                    "分块必须保留全部内容（原文 " + textNorm.length() + " 字，块内共 "
                            + joinedNorm.length() + " 字）");
        }
    }

    /** text 是否为 joined 的子序列（保序出现；重叠产生的重复内容不影响） */
    private static boolean isSubsequence(String text, String joined) {
        int idx = 0;
        for (char ch : text.toCharArray()) {
            int found = joined.indexOf(ch, idx);
            if (found < 0) {
                return false;
            }
            idx = found + 1;
        }
        return true;
    }

    @Test
    void blankInputGivesEmptyList() {
        assertTrue(KbChunker.chunk(null, 600, 80).isEmpty());
        assertTrue(KbChunker.chunk("   \n \n", 600, 80).isEmpty());
    }

    @Test
    void chunkOrderPreserved() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("段落编号").append(String.format("%03d", i)).append("，内容内容内容内容内容内容内容内容内容内容。\n\n");
        }
        List<String> chunks = KbChunker.chunk(sb.toString(), 300, 30);
        assertFalse(chunks.isEmpty());
        // 首块含 000，最后一块含最大编号
        assertTrue(chunks.get(0).contains("000"));
        assertTrue(chunks.get(chunks.size() - 1).contains("029"));
    }
}
