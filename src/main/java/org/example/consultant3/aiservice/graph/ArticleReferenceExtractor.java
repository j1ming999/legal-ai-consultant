package org.example.consultant3.aiservice.graph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法条引用关系抽取器。
 * <p>
 * 从一条法条的正文中抽取它引用的其他法条条号，用于构建知识图谱的
 * {@code (:Article)-[:REFERS_TO]->(:Article)} 引用边。
 * <p>
 * 支持两类引用：
 * <ul>
 *   <li><b>同法律引用</b>：本法第一百四十八条 / 依照第二条 / 第二百四十三条、第二百四十五条</li>
 *   <li><b>跨法律引用</b>：依照《劳动合同法》第三十二条 / 参照《民法典》第一千一百六十五条</li>
 * </ul>
 * 同法律引用返回条号集合；跨法律引用返回 Map（目标法律名 → 目标条号集合）。
 */
public class ArticleReferenceExtractor {

    private static final String CN_NUMS = "一二三四五六七八九十百千零○〇0-9";
    private static final String LAW_NAME_WITH_BOOKMARKS = "《([^》]+)》";

    /** 匹配单个条号，如 "第一百四十八条"、"第二十六条之一" */
    private static final Pattern ARTICLE_REF_PATTERN =
            Pattern.compile("第[" + CN_NUMS + "]+条(?:之[" + CN_NUMS + "]+)?");

    /** 匹配跨法律引用，如 "《劳动合同法》第三十二条" 、 "《民法典》第一千一百六十五条" */
    private static final Pattern CROSS_LAW_REF_PATTERN =
            Pattern.compile(LAW_NAME_WITH_BOOKMARKS + "\\s*第[" + CN_NUMS + "]+条(?:之[" + CN_NUMS + "]+)?");

    /**
     * 跨法律引用条目：目标法律名 → 目标条号集合。
     */
    public static class CrossLawRef {
        public final String targetLawName;
        public final String targetArticle;

        public CrossLawRef(String targetLawName, String targetArticle) {
            this.targetLawName = targetLawName;
            this.targetArticle = targetArticle;
        }

        @Override
        public String toString() {
            return "《" + targetLawName + "》" + targetArticle;
        }
    }

    /**
     * 从法条正文中抽取所有被引用的条号（同一部法律内）。
     *
     * @param text             法条正文
     * @param selfArticleNumber 当前法条自身的条号（排除自引用）
     * @return 被引用的条号集合（已去重）
     */
    public Set<String> extractReferences(String text, String selfArticleNumber) {
        Set<String> refs = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return refs;
        }

        String self = normalize(selfArticleNumber);
        Matcher matcher = ARTICLE_REF_PATTERN.matcher(text);
        while (matcher.find()) {
            String ref = normalize(matcher.group());
            if (ref == null || ref.isEmpty() || ref.equals(self)) {
                continue;
            }
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 从法条正文中抽取跨法律引用：定位出 {@code 《XX法》第Y条} 模式，
     * 给出目标法律名称和条号。
     *
     * @param text 法条正文
     * @return 跨法律引用集合（去重，保持顺序）
     */
    public Set<CrossLawRef> extractCrossLawReferences(String text) {
        Set<CrossLawRef> refs = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return refs;
        }

        Matcher matcher = CROSS_LAW_REF_PATTERN.matcher(text);
        while (matcher.find()) {
            String targetLaw = matcher.group(1).trim();
            String articleStr = matcher.group(0); // 完整匹配含条号
            String articleNum = normalize(articleStr);
            if (targetLaw.isEmpty() || articleNum == null) {
                continue;
            }
            refs.add(new CrossLawRef(targetLaw, articleNum));
        }
        return refs;
    }

    /**
     * 规范化条号：去除"之X"等后缀差异，统一为"第X条"的核心形式。
     */
    private String normalize(String articleNumber) {
        if (articleNumber == null) {
            return null;
        }
        Matcher m = ARTICLE_REF_PATTERN.matcher(articleNumber.trim());
        if (m.find()) {
            return m.group();
        }
        return null;
    }
}
