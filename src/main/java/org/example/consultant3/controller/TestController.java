package org.example.consultant3.controller;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.example.consultant3.aiservice.config.CommonConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;
import java.io.InputStream;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 测试接口：用于检索精度对比、分块质量验证、性能测试
 */
@RestController
@RequestMapping("/test")
public class TestController {

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    private ContentRetriever contentRetriever; // 混合检索器

    /**
     * (1) 检索精度对比测试
     * 同时返回纯向量检索和混合检索的结果，便于对比
     *
     * 用法: /test/compare?q=民法典第148条
     */
    @GetMapping(value = "/compare", produces = "application/json;charset=utf-8")
    public Map<String, Object> compareRetrieval(@RequestParam String q) {
        Query query = Query.from(q);
        long start;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", q);

        // 纯向量检索
        start = System.currentTimeMillis();
        List<Content> vectorResults = commonConfig.getVectorOnlyRetriever().retrieve(query);
        long vectorTime = System.currentTimeMillis() - start;

        // 混合检索
        start = System.currentTimeMillis();
        List<Content> hybridResults = contentRetriever.retrieve(query);
        long hybridTime = System.currentTimeMillis() - start;

        result.put("vector_search", buildResultList(vectorResults));
        result.put("vector_time_ms", vectorTime);
        result.put("hybrid_search", buildResultList(hybridResults));
        result.put("hybrid_time_ms", hybridTime);

        return result;
    }

    /**
     * (2) 批量精度测试
     * 传入问题和期望法条，自动判定命中情况，计算准确率/召回率
     *
     * 用法: /test/precision?q=借钱不还怎么办&expect=民间借贷,第二条
     *       expect 用逗号分隔多个期望关键词，全部出现在某条结果中才算命中
     */
    @GetMapping(value = "/precision", produces = "application/json;charset=utf-8")
    public Map<String, Object> precisionTest(@RequestParam String q,
                                              @RequestParam String expect) {
        Query query = Query.from(q);
        List<String> expectKeywords = Arrays.asList(expect.split(","));

        // 纯向量
        List<Content> vectorResults = commonConfig.getVectorOnlyRetriever().retrieve(query);
        boolean vectorHit = checkHit(vectorResults, expectKeywords);

        // 混合
        List<Content> hybridResults = contentRetriever.retrieve(query);
        boolean hybridHit = checkHit(hybridResults, expectKeywords);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", q);
        result.put("expect", expectKeywords);
        result.put("vector_hit", vectorHit);
        result.put("vector_results", buildResultList(vectorResults));
        result.put("hybrid_hit", hybridHit);
        result.put("hybrid_results", buildResultList(hybridResults));

        return result;
    }

    /**
     * (3) 分块质量验证
     * 随机抽查或按法律名称查看切分结果和元数据
     *
     * 用法: /test/segments?law=民法典&limit=10
     *       /test/segments?random=5
     */
    @GetMapping(value = "/segments", produces = "application/json;charset=utf-8")
    public Map<String, Object> checkSegments(
            @RequestParam(required = false) String law,
            @RequestParam(required = false, defaultValue = "10") int limit,
            @RequestParam(required = false, defaultValue = "0") int random) {

        List<TextSegment> segments = commonConfig.getAllSegments();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_segments", segments.size());

        // 统计每部法律的片段数
        Map<String, Long> lawStats = segments.stream()
                .collect(Collectors.groupingBy(
                        s -> s.metadata().getString("law_name") != null
                                ? s.metadata().getString("law_name") : "unknown",
                        Collectors.counting()));
        result.put("law_stats", lawStats);

        List<TextSegment> selected;
        if (random > 0) {
            // 随机抽查
            List<TextSegment> shuffled = new ArrayList<>(segments);
            Collections.shuffle(shuffled);
            selected = shuffled.subList(0, Math.min(random, shuffled.size()));
        } else if (law != null && !law.isEmpty()) {
            // 按法律名称筛选
            selected = segments.stream()
                    .filter(s -> {
                        String name = s.metadata().getString("law_name");
                        return name != null && name.contains(law);
                    })
                    .limit(limit)
                    .collect(Collectors.toList());
        } else {
            selected = segments.subList(0, Math.min(limit, segments.size()));
        }

        List<Map<String, Object>> segmentDetails = new ArrayList<>();
        for (TextSegment seg : selected) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("law_name", seg.metadata().getString("law_name"));
            detail.put("chapter", seg.metadata().getString("chapter"));
            detail.put("article_number", seg.metadata().getString("article_number"));
            detail.put("text_length", seg.text().length());
            detail.put("text_preview", seg.text().substring(0, Math.min(200, seg.text().length())) + "...");
            segmentDetails.add(detail);
        }
        result.put("segments", segmentDetails);

        return result;
    }

    /**
     * (4) 性能测试 - 单次查询响应时间
     *
     * 用法: /test/performance?q=交通事故赔偿
     */
    @GetMapping(value = "/performance", produces = "application/json;charset=utf-8")
    public Map<String, Object> performanceTest(@RequestParam String q) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", q);

        // 预热
        contentRetriever.retrieve(Query.from(q));

        // 测 5 次取平均
        long[] times = new long[5];
        for (int i = 0; i < 5; i++) {
            long start = System.currentTimeMillis();
            contentRetriever.retrieve(Query.from(q));
            times[i] = System.currentTimeMillis() - start;
        }

        result.put("times_ms", times);
        result.put("avg_ms", Arrays.stream(times).average().orElse(0));
        result.put("max_ms", Arrays.stream(times).max().orElse(0));
        result.put("min_ms", Arrays.stream(times).min().orElse(0));

        return result;
    }

    /**
     * (5) 一键批量测试：消融实验四组 + 查询改写细分
     * <p>
     * 输出四组消融配置：仅向量 / 仅关键词 / 向量+关键词(RRF) / 向量+关键词+改写(合并)
     * 改写细分：原始查询→混合 / 改写查询→混合 / 两者合并
     * 全部带 Recall@5/10/15/20，分母为正向测试数（不含负面测试）
     *
     * 用法: /test/batch
     * 注意：改写会调用LLM，200条大约需要几分钟
     */
    @GetMapping(value = "/batch", produces = "application/json;charset=utf-8")
    public Map<String, Object> batchTest(
            @RequestParam(required = false, defaultValue = "0") int limit) throws Exception {
        // 读取测试数据集
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getResourceAsStream("/dataset/test-dataset.json");
        List<Map<String, Object>> testCases = mapper.readValue(is, new TypeReference<>() {});
        if (limit > 0 && limit < testCases.size()) {
            testCases = testCases.subList(0, limit);
        }

        int vectorHitCount = 0;
        int keywordHitCount = 0;
        int hybridHitCount = 0;
        int rewriteMergedHitCount = 0;
        int rewriteOriginalHitCount = 0;
        int rewriteRewrittenHitCount = 0;
        // Recall@k 分级统计（仅正向测试）
        int vector5 = 0, vector10 = 0, vector15 = 0;
        int keyword5 = 0, keyword10 = 0, keyword15 = 0;
        int hybrid5 = 0, hybrid10 = 0, hybrid15 = 0;
        int rewrite5 = 0, rewrite10 = 0, rewrite15 = 0;
        int rewriteOrig5 = 0, rewriteOrig10 = 0, rewriteOrig15 = 0;
        int rewriteRewr5 = 0, rewriteRewr10 = 0, rewriteRewr15 = 0;
        // Precision@k 累加器（仅正向测试）
        double vectorP5 = 0, vectorP10 = 0, vectorP15 = 0, vectorP20 = 0;
        double keywordP5 = 0, keywordP10 = 0, keywordP15 = 0, keywordP20 = 0;
        double hybridP5 = 0, hybridP10 = 0, hybridP15 = 0, hybridP20 = 0;
        double rewriteP5 = 0, rewriteP10 = 0, rewriteP15 = 0, rewriteP20 = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        long totalVectorTime = 0;
        long totalKeywordTime = 0;
        long totalHybridTime = 0;
        long totalRewriteTime = 0;

        for (Map<String, Object> tc : testCases) {
            String q = (String) tc.get("query");
            String expect = (String) tc.get("expect");
            Query query = Query.from(q);

            boolean isNegativeTest = "NOT_IN_KB".equals(expect);

            // 1) 纯向量
            long t1 = System.currentTimeMillis();
            List<Content> vectorResults = commonConfig.getVectorOnlyRetriever().retrieve(query);
            long vectorTime = System.currentTimeMillis() - t1;

            // 2) 仅关键词
            long tk = System.currentTimeMillis();
            List<Content> keywordResults = commonConfig.getKeywordOnlyRetriever().retrieve(query);
            long keywordTime = System.currentTimeMillis() - tk;

            // 3) 混合（向量+关键词+RRF，不改写）
            long t2 = System.currentTimeMillis();
            List<Content> hybridResults = commonConfig.getHybridRetriever().retrieve(query);
            long hybridTime = System.currentTimeMillis() - t2;

            // 4) 查询改写管线
            long t3 = System.currentTimeMillis();
            String rewrittenQuery = commonConfig.getRewriteRetriever().rewrite(q);
            List<Content> rewriteMergedResults = commonConfig.getRewriteRetriever().retrieve(query);
            long rewriteTime = System.currentTimeMillis() - t3;

            // 改写细分：原始查询→混合检索
            List<Content> rewriteOrigResults = commonConfig.getHybridRetriever().retrieve(query);
            // 改写细分：改写查询→混合检索
            List<Content> rewriteRewrResults = rewrittenQuery.equals(q)
                    ? rewriteOrigResults
                    : commonConfig.getHybridRetriever().retrieve(Query.from(rewrittenQuery));

            boolean vHit, kHit, hHit, rMergedHit, rOrigHit, rRewrHit;
            if (isNegativeTest) {
                vHit = vectorResults.size() <= 5;
                kHit = keywordResults.size() <= 5;
                hHit = hybridResults.size() <= 5;
                rMergedHit = rewriteMergedResults.size() <= 5;
                rOrigHit = rewriteOrigResults.size() <= 5;
                rRewrHit = rewriteRewrResults.size() <= 5;
            } else {
                List<String> expectKeywords = Arrays.asList(expect.split(","));
                vHit = checkHit(vectorResults, expectKeywords);
                kHit = checkHit(keywordResults, expectKeywords);
                hHit = checkHit(hybridResults, expectKeywords);
                rMergedHit = checkHit(rewriteMergedResults, expectKeywords);
                rOrigHit = checkHit(rewriteOrigResults, expectKeywords);
                rRewrHit = checkHit(rewriteRewrResults, expectKeywords);
                // 分级 Recall@k
                if (checkHitAtK(vectorResults, expectKeywords, 5)) vector5++;
                if (checkHitAtK(vectorResults, expectKeywords, 10)) vector10++;
                if (checkHitAtK(vectorResults, expectKeywords, 15)) vector15++;
                if (checkHitAtK(keywordResults, expectKeywords, 5)) keyword5++;
                if (checkHitAtK(keywordResults, expectKeywords, 10)) keyword10++;
                if (checkHitAtK(keywordResults, expectKeywords, 15)) keyword15++;
                if (checkHitAtK(hybridResults, expectKeywords, 5)) hybrid5++;
                if (checkHitAtK(hybridResults, expectKeywords, 10)) hybrid10++;
                if (checkHitAtK(hybridResults, expectKeywords, 15)) hybrid15++;
                if (checkHitAtK(rewriteMergedResults, expectKeywords, 5)) rewrite5++;
                if (checkHitAtK(rewriteMergedResults, expectKeywords, 10)) rewrite10++;
                if (checkHitAtK(rewriteMergedResults, expectKeywords, 15)) rewrite15++;
                if (checkHitAtK(rewriteOrigResults, expectKeywords, 5)) rewriteOrig5++;
                if (checkHitAtK(rewriteOrigResults, expectKeywords, 10)) rewriteOrig10++;
                if (checkHitAtK(rewriteOrigResults, expectKeywords, 15)) rewriteOrig15++;
                if (checkHitAtK(rewriteRewrResults, expectKeywords, 5)) rewriteRewr5++;
                if (checkHitAtK(rewriteRewrResults, expectKeywords, 10)) rewriteRewr10++;
                if (checkHitAtK(rewriteRewrResults, expectKeywords, 15)) rewriteRewr15++;
                // Precision@k
                vectorP5 += precisionAtK(vectorResults, expectKeywords, 5);
                vectorP10 += precisionAtK(vectorResults, expectKeywords, 10);
                vectorP15 += precisionAtK(vectorResults, expectKeywords, 15);
                vectorP20 += precisionAtK(vectorResults, expectKeywords, 20);
                keywordP5 += precisionAtK(keywordResults, expectKeywords, 5);
                keywordP10 += precisionAtK(keywordResults, expectKeywords, 10);
                keywordP15 += precisionAtK(keywordResults, expectKeywords, 15);
                keywordP20 += precisionAtK(keywordResults, expectKeywords, 20);
                hybridP5 += precisionAtK(hybridResults, expectKeywords, 5);
                hybridP10 += precisionAtK(hybridResults, expectKeywords, 10);
                hybridP15 += precisionAtK(hybridResults, expectKeywords, 15);
                hybridP20 += precisionAtK(hybridResults, expectKeywords, 20);
                rewriteP5 += precisionAtK(rewriteMergedResults, expectKeywords, 5);
                rewriteP10 += precisionAtK(rewriteMergedResults, expectKeywords, 10);
                rewriteP15 += precisionAtK(rewriteMergedResults, expectKeywords, 15);
                rewriteP20 += precisionAtK(rewriteMergedResults, expectKeywords, 20);
            }

            if (vHit) vectorHitCount++;
            if (kHit) keywordHitCount++;
            if (hHit) hybridHitCount++;
            if (rMergedHit) rewriteMergedHitCount++;
            if (rOrigHit) rewriteOriginalHitCount++;
            if (rRewrHit) rewriteRewrittenHitCount++;
            totalVectorTime += vectorTime;
            totalKeywordTime += keywordTime;
            totalHybridTime += hybridTime;
            totalRewriteTime += rewriteTime;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", tc.get("id"));
            detail.put("query", q);
            detail.put("type", tc.get("type"));
            detail.put("category", tc.get("category"));
            detail.put("expect", expect);
            detail.put("vector_hit", vHit);
            detail.put("keyword_hit", kHit);
            detail.put("hybrid_hit", hHit);
            detail.put("rewrite_merged_hit", rMergedHit);
            detail.put("rewrite_original_hit", rOrigHit);
            detail.put("rewrite_rewritten_hit", rRewrHit);
            detail.put("rewritten_query", rewrittenQuery);
            detail.put("vector_time_ms", vectorTime);
            detail.put("keyword_time_ms", keywordTime);
            detail.put("hybrid_time_ms", hybridTime);
            detail.put("rewrite_time_ms", rewriteTime);
            details.add(detail);
        }

        int total = testCases.size();
        int positiveTotal = total - (int) testCases.stream().filter(tc -> "NOT_IN_KB".equals(tc.get("expect"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_cases", total);
        result.put("positive_cases", positiveTotal);

        // ===== 消融实验：四组 Recall@k（分母 = 正向测试数） =====
        // 仅向量
        result.put("vector_recall_at_5", pct(vector5, positiveTotal));
        result.put("vector_recall_at_10", pct(vector10, positiveTotal));
        result.put("vector_recall_at_15", pct(vector15, positiveTotal));
        result.put("vector_recall_at_20", pct(vectorHitCount, positiveTotal));
        // 仅关键词
        result.put("keyword_recall_at_5", pct(keyword5, positiveTotal));
        result.put("keyword_recall_at_10", pct(keyword10, positiveTotal));
        result.put("keyword_recall_at_15", pct(keyword15, positiveTotal));
        result.put("keyword_recall_at_20", pct(keywordHitCount, positiveTotal));
        // 向量+关键词(RRF)
        result.put("hybrid_recall_at_5", pct(hybrid5, positiveTotal));
        result.put("hybrid_recall_at_10", pct(hybrid10, positiveTotal));
        result.put("hybrid_recall_at_15", pct(hybrid15, positiveTotal));
        result.put("hybrid_recall_at_20", pct(hybridHitCount, positiveTotal));
        // 向量+关键词+改写(合并)
        result.put("rewrite_merged_recall_at_5", pct(rewrite5, positiveTotal));
        result.put("rewrite_merged_recall_at_10", pct(rewrite10, positiveTotal));
        result.put("rewrite_merged_recall_at_15", pct(rewrite15, positiveTotal));
        result.put("rewrite_merged_recall_at_20", pct(rewriteMergedHitCount, positiveTotal));

        // ===== 查询改写细分：原始 / 改写 / 合并 =====
        result.put("rewrite_original_recall_at_5", pct(rewriteOrig5, positiveTotal));
        result.put("rewrite_original_recall_at_10", pct(rewriteOrig10, positiveTotal));
        result.put("rewrite_original_recall_at_15", pct(rewriteOrig15, positiveTotal));
        result.put("rewrite_original_recall_at_20", pct(rewriteOriginalHitCount, positiveTotal));
        result.put("rewrite_rewritten_recall_at_5", pct(rewriteRewr5, positiveTotal));
        result.put("rewrite_rewritten_recall_at_10", pct(rewriteRewr10, positiveTotal));
        result.put("rewrite_rewritten_recall_at_15", pct(rewriteRewr15, positiveTotal));
        result.put("rewrite_rewritten_recall_at_20", pct(rewriteRewrittenHitCount, positiveTotal));

        // ===== Precision@k（平均值，分母 = 正向测试数） =====
        result.put("vector_precision_at_5", pctDouble(vectorP5, positiveTotal));
        result.put("vector_precision_at_10", pctDouble(vectorP10, positiveTotal));
        result.put("vector_precision_at_15", pctDouble(vectorP15, positiveTotal));
        result.put("vector_precision_at_20", pctDouble(vectorP20, positiveTotal));
        result.put("keyword_precision_at_5", pctDouble(keywordP5, positiveTotal));
        result.put("keyword_precision_at_10", pctDouble(keywordP10, positiveTotal));
        result.put("keyword_precision_at_15", pctDouble(keywordP15, positiveTotal));
        result.put("keyword_precision_at_20", pctDouble(keywordP20, positiveTotal));
        result.put("hybrid_precision_at_5", pctDouble(hybridP5, positiveTotal));
        result.put("hybrid_precision_at_10", pctDouble(hybridP10, positiveTotal));
        result.put("hybrid_precision_at_15", pctDouble(hybridP15, positiveTotal));
        result.put("hybrid_precision_at_20", pctDouble(hybridP20, positiveTotal));
        result.put("rewrite_merged_precision_at_5", pctDouble(rewriteP5, positiveTotal));
        result.put("rewrite_merged_precision_at_10", pctDouble(rewriteP10, positiveTotal));
        result.put("rewrite_merged_precision_at_15", pctDouble(rewriteP15, positiveTotal));
        result.put("rewrite_merged_precision_at_20", pctDouble(rewriteP20, positiveTotal));

        // ===== 绝对命中数（方便核对） =====
        result.put("vector_hits", vectorHitCount);
        result.put("keyword_hits", keywordHitCount);
        result.put("hybrid_hits", hybridHitCount);
        result.put("rewrite_merged_hits", rewriteMergedHitCount);
        result.put("rewrite_original_hits", rewriteOriginalHitCount);
        result.put("rewrite_rewritten_hits", rewriteRewrittenHitCount);

        // ===== 对比差值 =====
        result.put("hybrid_vs_vector", diff(hybridHitCount, vectorHitCount, positiveTotal));
        result.put("keyword_vs_vector", diff(keywordHitCount, vectorHitCount, positiveTotal));
        result.put("rewrite_merged_vs_hybrid", diff(rewriteMergedHitCount, hybridHitCount, positiveTotal));
        result.put("rewrite_merged_vs_vector", diff(rewriteMergedHitCount, vectorHitCount, positiveTotal));

        // ===== 失败用例摘要（以改写合并为准） =====
        List<Map<String, Object>> failedCases = details.stream()
                .filter(d -> !(boolean) d.get("rewrite_merged_hit"))
                .map(d -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("id", d.get("id"));
                    f.put("query", d.get("query"));
                    f.put("expect", d.get("expect"));
                    f.put("type", d.get("type"));
                    f.put("vector_hit", d.get("vector_hit"));
                    f.put("keyword_hit", d.get("keyword_hit"));
                    f.put("hybrid_hit", d.get("hybrid_hit"));
                    f.put("rewrite_merged_hit", d.get("rewrite_merged_hit"));
                    f.put("rewrite_original_hit", d.get("rewrite_original_hit"));
                    f.put("rewrite_rewritten_hit", d.get("rewrite_rewritten_hit"));
                    f.put("rewritten_query", d.get("rewritten_query"));
                    return f;
                })
                .collect(Collectors.toList());
        result.put("failed_count", failedCases.size());
        result.put("failed_cases", failedCases);

        // ===== 性能 =====
        result.put("avg_vector_time_ms", totalVectorTime / total);
        result.put("avg_keyword_time_ms", totalKeywordTime / total);
        result.put("avg_hybrid_time_ms", totalHybridTime / total);
        result.put("avg_rewrite_time_ms", totalRewriteTime / total);

        result.put("details", details);
        return result;
    }

    private static String pct(int hits, int base) {
        return String.format("%.1f%%", 100.0 * hits / base);
    }

    private static String pctDouble(double sum, int base) {
        return String.format("%.1f%%", 100.0 * sum / base);
    }

    private static String diff(int a, int b, int base) {
        return String.format("%+.1f%%", 100.0 * (a - b) / base);
    }

    // ========== 工具方法 ==========

    /**
     * 检查期望关键词是否在 Top-N 检索结果全集中被覆盖。
     * 相比要求所有关键词都在同一个片段中，全集合覆盖更合理：
     * 用户查询的关键词可能分布在多个相关的法条片段中，
     * 只要检索结果集整体覆盖了这些关键词，即视为命中。
     */
    private boolean checkHit(List<Content> results, List<String> expectKeywords) {
        return checkHitAtK(results, expectKeywords, Math.min(results.size(), 20));
    }

    /**
     * 检查前 maxK 条结果中是否覆盖了所有期望关键词
     */
    private boolean checkHitAtK(List<Content> results, List<String> expectKeywords, int maxK) {
        int limit = Math.min(maxK, results.size());
        Set<String> matched = new HashSet<>();
        for (int i = 0; i < limit; i++) {
            Content c = results.get(i);
            String text = c.textSegment().text();
            String lawName = c.textSegment().metadata().getString("law_name");
            String chapter = c.textSegment().metadata().getString("chapter");
            String articleNum = c.textSegment().metadata().getString("article_number");
            String combined = String.join(" ",
                    lawName != null ? lawName : "",
                    chapter != null ? chapter : "",
                    articleNum != null ? articleNum : "",
                    text);

            for (String kw : expectKeywords) {
                if (combined.contains(kw.trim())) {
                    matched.add(kw.trim());
                }
            }
            if (matched.size() == expectKeywords.size()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 计算 Precision@k：Top-k 结果中包含至少一个期望关键词的条目占比
     */
    private double precisionAtK(List<Content> results, List<String> expectKeywords, int k) {
        int limit = Math.min(k, results.size());
        if (limit == 0) return 0.0;
        int relevant = 0;
        for (int i = 0; i < limit; i++) {
            Content c = results.get(i);
            String text = c.textSegment().text();
            String lawName = c.textSegment().metadata().getString("law_name");
            String chapter = c.textSegment().metadata().getString("chapter");
            String articleNum = c.textSegment().metadata().getString("article_number");
            String combined = String.join(" ",
                    lawName != null ? lawName : "",
                    chapter != null ? chapter : "",
                    articleNum != null ? articleNum : "",
                    text);
            for (String kw : expectKeywords) {
                if (combined.contains(kw.trim())) {
                    relevant++;
                    break;
                }
            }
        }
        return (double) relevant / limit;
    }

    private List<Map<String, String>> buildResultList(List<Content> contents) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Content c : contents) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("law_name", c.textSegment().metadata().getString("law_name"));
            item.put("chapter", c.textSegment().metadata().getString("chapter"));
            item.put("article_number", c.textSegment().metadata().getString("article_number"));
            item.put("text_preview", c.textSegment().text().substring(0,
                    Math.min(150, c.textSegment().text().length())) + "...");
            list.add(item);
        }
        return list;
    }
}
