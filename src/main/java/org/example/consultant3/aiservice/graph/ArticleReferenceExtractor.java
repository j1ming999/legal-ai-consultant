package org.example.consultant3.aiservice.graph;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法条引用关系抽取器。
 * <p>
 * 从一条法条的正文中抽取它引用的其他法条条号，用于构建知识图谱的
 * {@code (:Article)-[:REFERS_TO]->(:Article)} 引用边。
 * <p>
 * 识别的引用形式（均限定"条"结尾，不匹配"编/章/节"）：
 * <ul>
 *   <li>本法第一百四十八条</li>
 *   <li>依照第二条</li>
 *   <li>第二百四十三条、第二百四十五条（顿号连接的多条引用）</li>
 * </ul>
 * 抽取结果会按 {@link LegalDocumentSplitter} 相同的规则规范化为
 * "第X条"形式，保证与 {@code article_number} 元数据可对齐。
 */
public class ArticleReferenceExtractor {

    private static final String CN_NUMS = "一二三四五六七八九十百千零○〇0-9";

    /** 匹配单个条号，如 "第一百四十八条"、"第二十六条之一" */
    private static final Pattern ARTICLE_REF_PATTERN =
            Pattern.compile("第[" + CN_NUMS + "]+条(?:之[" + CN_NUMS + "]+)?");

    /**
     * 从法条正文中抽取所有被引用的条号。
     *
     * @param text          法条正文
     * @param selfArticleNumber 当前法条自身的条号（用于排除自引用），可为 null
     * @return 被引用的条号集合（规范化为"第X条"，保持出现顺序，已去重）
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
            if (ref == null || ref.isEmpty()) {
                continue;
            }
            // 排除自引用：法条正文开头就是自己的条号
            if (ref.equals(self)) {
                continue;
            }
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 规范化条号：去除"之X"等后缀差异，统一为"第X条"的核心形式。
     * 与 LegalDocumentSplitter 的 article_number 对齐。
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
