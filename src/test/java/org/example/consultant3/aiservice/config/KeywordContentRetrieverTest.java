package org.example.consultant3.aiservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：关键字提取、中文数字转换、命中率打分
 */
class KeywordContentRetrieverTest {

    private final KeywordContentRetriever retriever = new KeywordContentRetriever(List.of(), 20);

    // ==================== 中文数字转阿拉伯 ====================

    @ParameterizedTest
    @CsvSource({
            "1, 一",
            "10, 十",
            "15, 十五",
            "20, 二十",
            "99, 九十九",
            "100, 一百",
            "101, 一百零一",
            "110, 一百一十",
            "148, 一百四十八",
            "205, 二百零五",
            "999, 九百九十九",
            "1000, 一千",
            "1001, 一千零一",
            "1010, 一千零一十",
            "1100, 一千一百",
            "1260, 一千二百六十",
            "9999, 九千九百九十九",
    })
    @DisplayName("阿拉伯数字 → 中文数字转换")
    void arabicToChinese(int input, String expected) {
        assertEquals(expected, KeywordContentRetriever.arabicToChinese(input));
    }

    @Test
    @DisplayName("arabicToChinese(0) 返回空字符串")
    void arabicToChineseZeroReturnsEmpty() {
        assertEquals("", KeywordContentRetriever.arabicToChinese(0));
    }

    // ==================== 关键字提取 ====================

    @Test
    @DisplayName("提取关键字：停用词被过滤，标点被用作分隔")
    void extractKeywordsRemovesStopWords() {
        // 标点分隔：关键词被逗号自然分开
        List<String> kw = retriever.extractKeywords("民法典，违约，合同");
        assertTrue(kw.contains("民法典"));
        assertTrue(kw.contains("违约"));
        assertTrue(kw.contains("合同"));

        // 停用词被移除后不出现
        List<String> kw2 = retriever.extractKeywords("在民法典中，关于违约是，怎么规定的");
        assertFalse(kw2.contains("在"));
        assertFalse(kw2.contains("的"));
        assertFalse(kw2.contains("是"));
    }

    @Test
    @DisplayName("提取关键字：不足2字符的词被过滤")
    void extractKeywordsFiltersShortWords() {
        List<String> kw = retriever.extractKeywords("我 的 和 你");
        assertTrue(kw.isEmpty());
    }

    @Test
    @DisplayName("提取关键字：阿拉伯数字条号自动转中文")
    void extractKeywordsConvertsArabicArticleNumber() {
        List<String> kw = retriever.extractKeywords("民法典第148条");
        assertTrue(kw.contains("第一百四十八条"), "应包含转换后的 '第一百四十八条'，实际: " + kw);
    }

    @Test
    @DisplayName("提取关键字：标点分隔的复合查询")
    void extractKeywordsComplexQuery() {
        // 用标点把关键词自然分隔开
        List<String> kw = retriever.extractKeywords("老婆，家暴，人身保护令");
        assertTrue(kw.contains("老婆"));
        assertTrue(kw.contains("家暴"));
        assertTrue(kw.contains("人身保护令"));
    }

    // ==================== 边界情况 ====================

    @Test
    @DisplayName("提取关键字：空查询返回空列表")
    void extractKeywordsEmptyQuery() {
        List<String> kw = retriever.extractKeywords("");
        assertTrue(kw.isEmpty());
    }

    @Test
    @DisplayName("提取关键字：纯标点查询返回空列表")
    void extractKeywordsOnlyPunctuation() {
        List<String> kw = retriever.extractKeywords("？。，！");
        assertTrue(kw.isEmpty());
    }

    // ==================== arabicToChinese 边界 ====================

    @Test
    @DisplayName("arabicToChinese 负数返回空字符串")
    void arabicToChineseNegative() {
        assertEquals("", KeywordContentRetriever.arabicToChinese(-5));
    }
}
