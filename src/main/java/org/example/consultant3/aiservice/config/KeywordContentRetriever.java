package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 基于关键词匹配的法律文档检索器。
 * <p>
 * 核心能力：
 * 1. 中文关键词提取（按标点/虚词切分）
 * 2. 阿拉伯数字条号 → 中文条号自动转换（"第148条" → "第一百四十八条"）
 * 3. 对所有文本片段做关键词命中率打分，返回 Top-N
 */
public class KeywordContentRetriever implements ContentRetriever {

    private final List<TextSegment> segments;
    private final int maxResults;

    /** 匹配查询中的阿拉伯数字条号，如 "第148条"、"第26条之一" */
    private static final Pattern ARABIC_ARTICLE = Pattern.compile("第(\\d+)条");

    private static final String[] CN_DIGITS = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    public KeywordContentRetriever(List<TextSegment> segments, int maxResults) {
        this.segments = segments;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<String> keywords = extractKeywords(query.text());
        if (keywords.isEmpty()) {
            return List.of();
        }

        return segments.stream()
                .map(seg -> {
                    String lawName = seg.metadata().getString("law_name");
                    String chapter = seg.metadata().getString("chapter");
                    String combined = seg.text()
                            + (lawName != null ? " " + lawName : "")
                            + (chapter != null ? " " + chapter : "");
                    return new ScoredSegment(seg, score(combined, keywords));
                })
                .sorted(Comparator.comparingDouble(ScoredSegment::score).reversed())
                .limit(maxResults)
                .map(s -> Content.from(s.segment))
                .collect(Collectors.toList());
    }

    /**
     * 从用户查询中提取关键词：
     * 1. 去除常见虚词/语气词
     * 2. 按标点和空格切分
     * 3. 将阿拉伯数字条号转为中文条号作为额外关键词
     */
    List<String> extractKeywords(String query) {
        // 去除常见虚词
        String cleaned = query.replaceAll("[的了吗呢吧啊呀哦么在是有被把给让跟和与及]", " ");
        // 按标点、空格切分
        cleaned = cleaned.replaceAll("[，。？！、；：\u201c\u201d\u2018\u2019（）《》【】\\s]+", " ").trim();

        List<String> keywords = new ArrayList<>();
        for (String part : cleaned.split("\\s+")) {
            if (part.length() >= 2) {
                keywords.add(part);
            }
        }

        // 阿拉伯数字条号 → 中文条号（如 "第148条" → "第一百四十八条"）
        Matcher m = ARABIC_ARTICLE.matcher(query);
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            keywords.add("第" + arabicToChinese(num) + "条");
        }

        return keywords;
    }

    /**
     * 计算关键词命中率：命中关键词数 / 总关键词数
     */
    private double score(String text, List<String> keywords) {
        int hits = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) hits++;
        }
        return (double) hits / keywords.size();
    }

    /**
     * 阿拉伯数字转中文数字（支持 1~9999）
     * 例：148 → "一百四十八"，205 → "二百零五"，1260 → "一千二百六十"
     */
    static String arabicToChinese(int num) {
        if (num <= 0) return "";

        StringBuilder sb = new StringBuilder();

        if (num >= 1000) {
            sb.append(CN_DIGITS[num / 1000]).append("千");
            num %= 1000;
            if (num > 0 && num < 100) sb.append("零");
        }
        if (num >= 100) {
            sb.append(CN_DIGITS[num / 100]).append("百");
            num %= 100;
            if (num > 0 && num < 10) sb.append("零");
        }
        if (num >= 10) {
            int tens = num / 10;
            // "十五" 而非 "一十五"（仅在最高位为十位时省略"一"）
            if (sb.isEmpty() && tens == 1) {
                sb.append("十");
            } else {
                sb.append(CN_DIGITS[tens]).append("十");
            }
            num %= 10;
        }
        if (num > 0) {
            sb.append(CN_DIGITS[num]);
        }

        return sb.toString();
    }

    private record ScoredSegment(TextSegment segment, double score) {}
}
