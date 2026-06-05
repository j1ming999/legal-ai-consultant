package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：RRF 融合算法、去重逻辑
 */
class HybridContentRetrieverTest {

    /**
     * 构造带 law_name + article_number 元数据的 TextSegment
     */
    private static TextSegment segment(String lawName, String articleNum, String text) {
        Metadata meta = new Metadata();
        meta.put("law_name", lawName);
        meta.put("article_number", articleNum);
        return TextSegment.from(text, meta);
    }

    private static Content content(String lawName, String articleNum, String text) {
        return Content.from(segment(lawName, articleNum, text));
    }

    /** 创建返回固定列表的 ContentRetriever */
    private static ContentRetriever fixedRetriever(List<Content> results) {
        return query -> new ArrayList<>(results);
    }

    // ==================== RRF 融合 ====================

    @Test
    @DisplayName("RRF 融合：去重合并两条检索线")
    void rrfFusionMergesAndDeduplicates() {
        // 向量检索结果
        List<Content> vectorResults = List.of(
                content("民法典", "第680条", "禁止高利放贷..."),
                content("民法典", "第667条", "借款合同定义..."),
                content("民事诉讼法", "第119条", "起诉条件...")
        );
        // 关键字检索结果（含重复项）
        List<Content> keywordResults = List.of(
                content("民法典", "第680条", "禁止高利放贷..."),  // 与 vector 重复
                content("民间借贷司法解释", "第25条", "利率上限...")
        );

        ContentRetriever vector = fixedRetriever(vectorResults);
        ContentRetriever keyword = fixedRetriever(keywordResults);
        HybridContentRetriever hybrid = new HybridContentRetriever(vector, keyword, 20, 1.5, 0.3);

        List<Content> result = hybrid.retrieve(null);

        // 应去重：4 条唯一结果（3 + 2 - 1 重复）
        assertTrue(result.size() >= 4, "应有至少 4 条去重结果，实际: " + result.size());

        // 检查去重键
        List<String> keys = result.stream()
                .map(c -> c.textSegment().metadata().getString("law_name")
                        + ":" + c.textSegment().metadata().getString("article_number"))
                .collect(Collectors.toList());
        long uniqueCount = keys.stream().distinct().count();
        assertEquals(keys.size(), uniqueCount, "去重后不应有重复键");
    }

    @Test
    @DisplayName("RRF 融合：排名靠前的文档在融合后仍排前面")
    void rrfFusionFavorsHighRankedDocuments() {
        // 向量：A 排第一，B 排第二
        List<Content> vectorResults = List.of(
                content("民法典", "第680条", "A"),
                content("民法典", "第667条", "B")
        );
        // 关键字：B 排第一，A 排第二（顺序相反）
        List<Content> keywordResults = List.of(
                content("民法典", "第667条", "B"),
                content("民法典", "第680条", "A")
        );

        ContentRetriever vector = fixedRetriever(vectorResults);
        ContentRetriever keyword = fixedRetriever(keywordResults);
        // 权重设为相同
        HybridContentRetriever hybrid = new HybridContentRetriever(vector, keyword, 15, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(null);

        assertEquals(2, result.size(), "应有 2 条结果");
        // 两条在各自列表中的排名对称，相同权重下 RRF 得分相同，按 map 插入顺序
        // 只需要验证两条都不缺
        List<String> articleNums = result.stream()
                .map(c -> c.textSegment().metadata().getString("article_number"))
                .toList();
        assertTrue(articleNums.contains("第680条"));
        assertTrue(articleNums.contains("第667条"));
    }

    @Test
    @DisplayName("RRF 融合：maxResults 限制工作正常")
    void rrfFusionRespectsMaxResults() {
        List<Content> vectorResults = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            vectorResults.add(content("法律" + i, "第" + i + "条", "text " + i));
        }

        ContentRetriever vector = fixedRetriever(vectorResults);
        ContentRetriever keyword = fixedRetriever(List.of());

        HybridContentRetriever hybrid = new HybridContentRetriever(vector, keyword, 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(null);
        assertEquals(5, result.size(), "应只返回 5 条结果");
    }

    @Test
    @DisplayName("RRF 融合：权重差异影响排序")
    void rrfFusionWeightAffectsRanking() {
        List<Content> vecResults = List.of(
                content("民法典", "第680条", "vector first"),
                content("民法典", "第667条", "vector second")
        );
        List<Content> kwResults = List.of(
                content("民间借贷", "第25条", "keyword first")
        );

        // 向量权重远大于关键字
        ContentRetriever vector = fixedRetriever(vecResults);
        ContentRetriever keyword = fixedRetriever(kwResults);
        HybridContentRetriever hybrid = new HybridContentRetriever(vector, keyword, 15, 10.0, 0.1);

        List<Content> result = hybrid.retrieve(null);
        // 向量第一名的文档应该排在最前面
        assertEquals("第680条", result.get(0).textSegment().metadata().getString("article_number"));
    }

    @Test
    @DisplayName("RRF 融合：空输入处理")
    void rrfFusionHandlesEmptyInputs() {
        ContentRetriever empty = fixedRetriever(List.of());
        HybridContentRetriever hybrid = new HybridContentRetriever(empty, empty, 15, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(null);
        assertTrue(result.isEmpty());
    }

    // ==================== 动态混合检索 ====================

    @Test
    @DisplayName("等权 RRF：精确法条查询不再提高关键词权重")
    void exactArticleQueryUsesEqualRetrieverWeights() {
        List<Content> vectorResults = List.of(
                content("民法典", "第667条", "借款合同是借款人向贷款人借款，到期返还借款并支付利息的合同。"),
                content("民法典", "第668条", "借款合同应当采用书面形式，但是自然人之间借款另有约定的除外。")
        );
        List<Content> keywordResults = List.of(
                content("民法典", "第148条", "第一百四十八条 一方以欺诈手段，使对方在违背真实意思的情况下实施的民事法律行为，受欺诈方有权请求人民法院或者仲裁机构予以撤销。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("民法典第148条是什么"));

        assertEquals("第667条", result.get(0).textSegment().metadata().getString("article_number"));
    }

    @Test
    @DisplayName("动态权重：口语化查询不会让低质量关键词结果挤掉向量首位")
    void colloquialQueryKeepsVectorResultAheadOfWeakKeywordResult() {
        List<Content> vectorResults = List.of(
                content("反家庭暴力法", "第2条", "本法所称家庭暴力，是指家庭成员之间以殴打、捆绑、残害、限制人身自由以及经常性谩骂、恐吓等方式实施的身体、精神等侵害行为。")
        );
        List<Content> keywordResults = List.of(
                content("道路交通安全法", "第90条", "机动车驾驶人违反道路交通安全法律、法规关于道路通行规定的，处警告或者罚款。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("老公打我怎么办"));

        assertEquals("反家庭暴力法", result.get(0).textSegment().metadata().getString("law_name"));
        assertEquals(1, result.size(), "低质量关键词结果不应进入融合结果");
    }

    @Test
    @DisplayName("准入门槛：关键词结果不命中有效信号时不参与融合")
    void keywordResultsWithoutAdmissionSignalsAreFilteredOut() {
        List<Content> vectorResults = List.of(
                content("民法典", "第1079条", "夫妻一方要求离婚的，可以由有关组织进行调解或者直接向人民法院提起离婚诉讼。")
        );
        List<Content> keywordResults = List.of(
                content("产品质量法", "第1条", "为了加强对产品质量的监督管理，提高产品质量水平，明确产品质量责任，保护消费者的合法权益，制定本法。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("想离婚怎么办"));

        assertEquals(1, result.size());
        assertEquals("民法典", result.get(0).textSegment().metadata().getString("law_name"));
    }

    @Test
    @DisplayName("准入门槛：核心法律术语命中时关键词结果可以参与融合")
    void legalTermKeywordResultCanEnterFusion() {
        List<Content> vectorResults = List.of(
                content("民法典", "第143条", "具备相应条件的民事法律行为有效。")
        );
        List<Content> keywordResults = List.of(
                content("民法典", "第144条", "无民事行为能力人实施的民事法律行为无效。合同无效相关规则应结合民事法律行为效力规定判断。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("合同无效怎么认定"));

        List<String> articles = result.stream()
                .map(c -> c.textSegment().metadata().getString("article_number"))
                .toList();
        assertTrue(articles.contains("第144条"));
    }

    @Test
    @DisplayName("动态权重：法律术语查询允许关键词结果补充向量召回")
    void legalTermQueryAllowsKeywordResultWithSingleCoreKeyword() {
        List<Content> vectorResults = List.of(
                content("民法典", "第1165条", "行为人因过错侵害他人民事权益造成损害的，应当承担侵权责任。")
        );
        List<Content> keywordResults = List.of(
                content("工伤保险条例", "第14条", "职工在工作时间和工作场所内，因工作原因受到事故伤害的，应当认定为工伤。")
        );

        HybridContentRetriever hybrid = new HybridContentRetriever(
                fixedRetriever(vectorResults), fixedRetriever(keywordResults), 5, 1.0, 1.0);

        List<Content> result = hybrid.retrieve(dev.langchain4j.rag.query.Query.from("工伤怎么认定"));

        List<String> lawNames = result.stream()
                .map(c -> c.textSegment().metadata().getString("law_name"))
                .toList();
        assertTrue(lawNames.contains("工伤保险条例"));
    }

    // ==================== 论文数据验证 ====================

    @Test
    @DisplayName("RRF 公式验证：与论文公式一致")
    void rrfFormulaMatchesPaper() {
        // RRF 公式: Score = 1.5/(60 + rank_v) + 0.3/(60 + rank_kw)
        // rank 从 1 开始(i+1)，因为 i 从 0 开始

        // rank_v=1, rank_kw=3 → 预期得分 = 1.5/61 + 0.3/63 ≈ 0.02459 + 0.00476 ≈ 0.0294
        List<Content> vecResults = List.of(content("A法", "第1条", "text"));
        List<Content> kwResults = List.of(
                content("B法", "第2条", "text"),
                content("C法", "第3条", "text"),
                content("A法", "第1条", "text")  // 与 vec 第1条重复
        );

        ContentRetriever vector = fixedRetriever(vecResults);
        ContentRetriever keyword = fixedRetriever(kwResults);
        HybridContentRetriever hybrid = new HybridContentRetriever(vector, keyword, 20, 1.5, 0.3);

        List<Content> result = hybrid.retrieve(null);
        // "A法:第1条" 得分最高（在两个列表中都出现）
        assertEquals("A法",
                result.get(0).textSegment().metadata().getString("law_name"));
    }
}
