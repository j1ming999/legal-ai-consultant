package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：法律文档按"第X条"分片、元数据注入
 */
class LegalDocumentSplitterTest {

    private final LegalDocumentSplitter splitter = new LegalDocumentSplitter();

    /**
     * 构造测试用 Document
     */
    private static Document doc(String content, String fileName) {
        dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
        meta.put("file_name", fileName);
        return Document.from(content, meta);
    }

    // ==================== 正常分片 ====================

    @Test
    @DisplayName("按「第X条」正确切分多个法条")
    void splitByArticlePattern() {
        String content = """
                第一条　为了保护民事主体的合法权益，调整民事关系，维护社会和经济秩序，适应中国特色社会主义发展要求，弘扬社会主义核心价值观，根据宪法，制定本法。
                第二条　民法调整平等主体的自然人、法人和非法人组织之间的人身关系和财产关系。
                第三条　民事主体的人身权利、财产权利以及其他合法权益受法律保护，任何组织或者个人不得侵犯。""";

        List<TextSegment> segments = splitter.split(doc(content, "民法典.txt"));

        assertEquals(3, segments.size(), "应切分出 3 个法条");
        // 验证元数据
        for (TextSegment seg : segments) {
            assertEquals("民法典", seg.metadata().getString("law_name"));
            assertNotNull(seg.metadata().getString("article_number"));
        }
        assertEquals("第一条", segments.get(0).metadata().getString("article_number"));
        assertEquals("第二条", segments.get(1).metadata().getString("article_number"));
        assertEquals("第三条", segments.get(2).metadata().getString("article_number"));
    }

    @Test
    @DisplayName("识别编/章/节标题并注入到后续法条中")
    void splitInjectsChapterInfo() {
        String content = """
                第一编　总则
                第一章　基本规定
                第一条　为了保护民事主体的合法权益...
                第二条　民法调整平等主体的...
                第二章　自然人
                第三条　民事主体的人身权利...""";

        List<TextSegment> segments = splitter.split(doc(content, "民法典.txt"));

        assertEquals(3, segments.size());
        // 第一条、第二条的章节应为 "第一章 基本规定"
        assertTrue(segments.get(0).metadata().getString("chapter").contains("第一章"));
        assertTrue(segments.get(1).metadata().getString("chapter").contains("第一章"));
        // 第三条的章节应为 "第二章 自然人"
        assertTrue(segments.get(2).metadata().getString("chapter").contains("第二章"));
    }

    @Test
    @DisplayName("法条跨行时正确合并（款、项续行）")
    void splitMergesMultilineArticle() {
        String content = """
                第一条　为了科学推进房地产税改革，健全地方税体系，制定本法。
                房地产税按照下列方法计算：
                （一）计税依据为房地产评估值；
                （二）税率由省、自治区、直辖市人民政府在规定幅度内确定。
                第二条　本法所称房地产，是指...""";

        List<TextSegment> segments = splitter.split(doc(content, "房地产税法.txt"));

        assertEquals(2, segments.size());
        // 第一条应该包含跨行的全部内容
        assertTrue(segments.get(0).text().contains("（一）"));
        assertTrue(segments.get(0).text().contains("（二）"));
    }

    @Test
    @DisplayName("中文数字法条号正确识别（如第一百四十八条）")
    void splitRecognizesChineseArticleNumber() {
        String content = """
                第一百四十八条　一方以欺诈手段，使对方在违背真实意思的情况下实施的民事法律行为，受欺诈方有权请求人民法院或者仲裁机构予以撤销。
                第一百四十九条　第三人实施欺诈行为...""";

        List<TextSegment> segments = splitter.split(doc(content, "民法典.txt"));

        assertEquals(2, segments.size());
        assertEquals("第一百四十八条", segments.get(0).metadata().getString("article_number"));
        assertEquals("第一百四十九条", segments.get(1).metadata().getString("article_number"));
    }

    @Test
    @DisplayName("「第X条之一」格式识别")
    void splitRecognizesArticleWithSuffix() {
        String content = """
                第一百二十条之一　以暴力或者其他方法公然侮辱他人或者捏造事实诽谤他人，情节严重的，处三年以下有期徒刑。
                第一百二十条之二　有下列行为之一的...""";

        List<TextSegment> segments = splitter.split(doc(content, "刑法.txt"));

        assertEquals(2, segments.size());
        assertEquals("第一百二十条之一", segments.get(0).metadata().getString("article_number"));
        assertEquals("第一百二十条之二", segments.get(1).metadata().getString("article_number"));
    }

    // ==================== 回退方案 ====================

    @Test
    @DisplayName("无「第X条」结构的文档回退为段落分片")
    void fallbackSplitForUnstructuredDocument() {
        String content = """
                这是一份没有法条结构的说明文档。

                它包含多个自然段落，但没有「第X条」的格式。

                这种情况下应该按段落切分，每段不超过2000字符。""";

        List<TextSegment> segments = splitter.split(doc(content, "说明文档.txt"));

        assertTrue(segments.size() > 0, "回退方案也应产生分片");
        for (TextSegment seg : segments) {
            assertEquals("说明文档", seg.metadata().getString("law_name"));
            assertTrue(seg.text().contains("【说明文档】"), "文本应包含法律名称前缀");
        }
    }

    @Test
    @DisplayName("空文档抛出 IllegalArgumentException（LangChain4j 校验）")
    void splitEmptyDocument() {
        assertThrows(IllegalArgumentException.class, () ->
                splitter.split(doc("", "空文件.txt")));
    }

    // ==================== 元数据完整性 ====================

    @Test
    @DisplayName("每个分片都包含 law_name 元数据")
    void splitAlwaysIncludesLawName() {
        String content = """
                第一条　测试内容一。
                第二条　测试内容二。""";

        List<TextSegment> segments = splitter.split(doc(content, "测试法.txt"));

        for (TextSegment seg : segments) {
            assertEquals("测试法", seg.metadata().getString("law_name"));
        }
    }

    @Test
    @DisplayName("分片文本以【法律名】前缀开头")
    void splitTextStartsWithLawNamePrefix() {
        String content = "第一条　为了保护民事主体的合法权益，调整民事关系，维护社会和经济秩序。";

        List<TextSegment> segments = splitter.split(doc(content, "测试法.txt"));

        String text = segments.get(0).text();
        assertTrue(text.startsWith("【测试法】"),
                "文本应以【测试法】开头，实际: " + text.substring(0, Math.min(50, text.length())));
    }
}
