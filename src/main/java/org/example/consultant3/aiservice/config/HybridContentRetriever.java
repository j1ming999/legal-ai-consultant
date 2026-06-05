package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 混合检索器：将向量检索和关键词检索的结果通过 RRF（Reciprocal Rank Fusion）融合。
 * <p>
 * RRF 公式：score(d) = Σ 1/(k + rank_i)
 * 其中 k 为常数（默认60），rank_i 为文档在第 i 个检索器结果中的排名。
 * <p>
 * 优点：不需要归一化两种检索器的分数到相同尺度，直接基于排名融合。
 */
public class HybridContentRetriever implements ContentRetriever {

    private final ContentRetriever vectorRetriever;
    private final ContentRetriever keywordRetriever;
    private final int maxResults;
    private static final int RRF_K = 60;
    private final double vectorWeight;
    private final double keywordWeight;
    private static final Pattern ARTICLE_PATTERN = Pattern.compile("第[零一二三四五六七八九十百千万\\d]+[条款章节]");
    private static final Pattern LAW_NAME_PATTERN = Pattern.compile("(民法典|劳动合同法|劳动法|刑法|道路交通安全法|反家庭暴力法|工伤保险条例|消费者权益保护法|产品质量法|民事诉讼法|行政诉讼法|个人所得税法|物业管理条例|医疗事故处理条例)");
    private static final Set<String> LEGAL_TERMS = Set.of(
            "合同无效", "无效合同", "工伤", "工伤认定", "遗产继承", "法定继承", "劳动仲裁",
            "交通事故", "家庭暴力", "离婚诉讼", "民间借贷", "高利贷", "违约责任",
            "侵权责任", "劳动合同", "试用期", "经济补偿", "物业费", "医疗事故",
            "消费者权益", "产品质量", "行政处罚", "行政复议"
    );
    private static final double KEYWORD_SCORE_THRESHOLD = 0.4;

    public HybridContentRetriever(ContentRetriever vectorRetriever,
                                  ContentRetriever keywordRetriever,
                                  int maxResults,
                                  double vectorWeight,
                                  double keywordWeight) {
        this.vectorRetriever = vectorRetriever;
        this.keywordRetriever = keywordRetriever;
        this.maxResults = maxResults;
        this.vectorWeight = vectorWeight;
        this.keywordWeight = keywordWeight;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> vectorResults = vectorRetriever.retrieve(query);
        List<Content> keywordResults = keywordRetriever.retrieve(query);

        if (query == null) {
            return fuse(vectorResults, keywordResults, new RetrievalWeights(vectorWeight, keywordWeight));
        }

        String queryText = query.text();
        QueryType queryType = classify(queryText);
        List<Content> admittedKeywordResults = filterKeywordResults(queryText, keywordResults);
        RetrievalWeights weights = weightsFor(queryType, admittedKeywordResults);

        return fuse(vectorResults, admittedKeywordResults, weights);
    }

    /**
     * RRF 融合两个排名列表，按 law_name + article_number 去重
     */
    private List<Content> fuse(List<Content> listA, List<Content> listB, RetrievalWeights weights) {
        Map<String, RRFEntry> entries = new LinkedHashMap<>();

        for (int i = 0; i < listA.size(); i++) {
            Content c = listA.get(i);
            String key = deduplicationKey(c);
            entries.computeIfAbsent(key, k -> new RRFEntry(c))
                    .addScore(weights.vector() / (RRF_K + i + 1));
        }

        for (int i = 0; i < listB.size(); i++) {
            Content c = listB.get(i);
            String key = deduplicationKey(c);
            entries.computeIfAbsent(key, k -> new RRFEntry(c))
                    .addScore(weights.keyword() / (RRF_K + i + 1));
        }

        return entries.values().stream()
                .sorted(Comparator.comparingDouble(RRFEntry::score).reversed())
                .limit(maxResults)
                .map(RRFEntry::content)
                .collect(Collectors.toList());
    }

    private QueryType classify(String queryText) {
        if (ARTICLE_PATTERN.matcher(queryText).find() || LAW_NAME_PATTERN.matcher(queryText).find() || queryText.contains("《")) {
            return QueryType.EXACT;
        }
        if (LEGAL_TERMS.stream().anyMatch(queryText::contains)) {
            return QueryType.LEGAL_TERM;
        }
        return QueryType.COLLOQUIAL;
    }

    private RetrievalWeights weightsFor(QueryType queryType, List<Content> admittedKeywordResults) {
        if (admittedKeywordResults.isEmpty()) {
            return new RetrievalWeights(vectorWeight, 0.0);
        }
        return new RetrievalWeights(1.0, 1.0);
    }

    private List<Content> filterKeywordResults(String queryText, List<Content> keywordResults) {
        List<String> effectiveKeywords = extractEffectiveKeywords(queryText);
        return keywordResults.stream()
                .filter(content -> isAdmittedKeywordResult(queryText, content, effectiveKeywords))
                .collect(Collectors.toList());
    }

    private boolean isAdmittedKeywordResult(String queryText, Content content, List<String> effectiveKeywords) {
        TextSegment seg = content.textSegment();
        String lawName = seg.metadata().getString("law_name");
        String articleNumber = seg.metadata().getString("article_number");
        String text = seg.text()
                + (lawName != null ? " " + lawName : "")
                + (articleNumber != null ? " " + articleNumber : "");

        if (lawName != null && queryText.contains(lawName)) {
            return true;
        }
        if (articleNumber != null && queryText.contains(articleNumber.replaceAll("[^零一二三四五六七八九十百千万\\d]", ""))) {
            return true;
        }
        if (LEGAL_TERMS.stream().anyMatch(term -> queryText.contains(term) && text.contains(term))) {
            return true;
        }

        long hits = effectiveKeywords.stream().filter(text::contains).count();
        if (hits >= 2) {
            return true;
        }
        return !effectiveKeywords.isEmpty() && (double) hits / effectiveKeywords.size() >= KEYWORD_SCORE_THRESHOLD;
    }

    private List<String> extractEffectiveKeywords(String queryText) {
        String cleaned = queryText.replaceAll("[的了吗呢吧啊呀哦么在是有被把给让跟和与及怎么什么如何多少可以能不能]", " ");
        cleaned = cleaned.replaceAll("[，。？！、；：\"'“”‘’（）《》【】\\s]+", " ").trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(cleaned.split("\\s+"))
                .filter(part -> part.length() >= 2)
                .collect(Collectors.toList());
    }

    /**
     * 用 metadata 中的 law_name + article_number 作为去重键，
     * 避免同一法条因出现在两个检索结果中而重复返回。
     */
    private String deduplicationKey(Content content) {
        TextSegment seg = content.textSegment();
        String law = seg.metadata().getString("law_name");
        String article = seg.metadata().getString("article_number");
        if (law != null && article != null) {
            return law + ":" + article;
        }
        // fallback：用文本前100字符做 key
        String text = seg.text();
        return text.substring(0, Math.min(100, text.length()));
    }

    private enum QueryType {
        EXACT,
        LEGAL_TERM,
        COLLOQUIAL
    }

    private record RetrievalWeights(double vector, double keyword) {}

    private static class RRFEntry {
        private final Content content;
        private double score;

        RRFEntry(Content content) {
            this.content = content;
            this.score = 0;
        }

        void addScore(double s) {
            this.score += s;
        }

        double score() { return score; }
        Content content() { return content; }
    }
}
