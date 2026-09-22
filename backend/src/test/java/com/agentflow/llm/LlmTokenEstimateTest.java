package com.agentflow.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * token 估算：流式响应默认拿不到服务端用量，只能按文本长度估。
 * 这个项目的中文占比很高，用「字符数 / 4」这类 ASCII 假设会低报三四倍，成本看板就失去意义，
 * 所以按「中日韩字符 ≈ 1 token、ASCII ≈ 4 字符 1 token」分别计数——用例把这条口径钉住。
 */
class LlmTokenEstimateTest {

    @Test
    void emptyAndNullAreZero() {
        assertEquals(0, LlmClient.estimateTokens(null));
        assertEquals(0, LlmClient.estimateTokens(""));
    }

    @Test
    void chineseCountsRoughlyOneTokenPerChar() {
        int tokens = LlmClient.estimateTokens("分析链路并给出根因");
        assertEquals(9, tokens);
        // 远高于「字符数/4」的估算（那会得到 2~3），中文场景必须按字计
        assertTrue(tokens > "分析链路并给出根因".length() / 2);
    }

    @Test
    void asciiCountsRoughlyFourCharsPerToken() {
        assertEquals(2, LlmClient.estimateTokens("abcdefgh"));
        assertEquals(1, LlmClient.estimateTokens("abcd"));
    }

    @Test
    void mixedContentAddsBothParts() {
        // 4 个汉字 + 8 个 ASCII = 4 + 2
        assertEquals(6, LlmClient.estimateTokens("链路分析abcd efgh"));
    }

    @Test
    void neverReturnsZeroForNonEmptyText() {
        // 极短文本也至少要算 1 个 token，否则统计里会凭空出现「零成本调用」
        assertEquals(1, LlmClient.estimateTokens("a"));
        assertEquals(1, LlmClient.estimateTokens("中"));
    }
}
