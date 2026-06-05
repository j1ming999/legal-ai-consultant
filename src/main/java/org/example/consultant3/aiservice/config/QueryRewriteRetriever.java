package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询扩展检索器：用原始查询和 LLM 改写后的查询同时检索，合并去重结果。
 * <p>
 * 与简单替换不同，扩展策略不会丢失原始查询的精确匹配能力，
 * 同时通过改写查询补充口语化表达无法覆盖的法律术语。
 */
public class QueryRewriteRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteRetriever.class);

    private final ContentRetriever delegate;
    private final ChatModel chatModel;

    private static final String REWRITE_PROMPT =
            "你是一个法律检索查询优化器。请将用户的口语化问题改写为专业的法律检索查询。\n\n" +
            "改写规则：\n" +
            "1. 提取核心法律概念和关键词\n" +
            "2. 将口语表达替换为法律术语（如\"老公打我\"→\"家庭暴力\"）\n" +
            "3. 补充可能相关的法律名称（如\"反家庭暴力法\"\"民法典\"）\n" +
            "4. 如果用户提到具体法条编号，保留原样\n" +
            "5. 只输出改写后的查询，不要解释\n\n" +
            "用户问题：%s\n" +
            "改写后的检索查询：";

    public QueryRewriteRetriever(ContentRetriever delegate, ChatModel chatModel) {
        this.delegate = delegate;
        this.chatModel = chatModel;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String original = query.text();
        String rewritten = rewrite(original);
        log.info("查询改写：[{}] → [{}]", original, rewritten);

        // 用原始查询检索
        List<Content> originalResults = delegate.retrieve(query);

        // 如果改写结果和原始相同，直接返回
        if (rewritten.equals(original)) {
            return originalResults;
        }

        // 用改写查询检索
        List<Content> rewrittenResults = delegate.retrieve(Query.from(rewritten));

        // 合并去重：原始结果优先
        return mergeResults(originalResults, rewrittenResults);
    }

    /**
     * 交错合并两组检索结果：primary[0], secondary[0], primary[1], secondary[1], ...
     * 按 law_name + article_number 去重，保证改写查询的结果有机会进入最终列表。
     */
    private List<Content> mergeResults(List<Content> primary, List<Content> secondary) {
        Map<String, Content> seen = new LinkedHashMap<>();
        int maxLen = Math.max(primary.size(), secondary.size());

        for (int i = 0; i < maxLen; i++) {
            if (i < primary.size()) {
                String key = deduplicationKey(primary.get(i));
                seen.putIfAbsent(key, primary.get(i));
            }
            if (i < secondary.size()) {
                String key = deduplicationKey(secondary.get(i));
                seen.putIfAbsent(key, secondary.get(i));
            }
        }

        return seen.values().stream()
                .limit(20)
                .collect(Collectors.toList());
    }

    private String deduplicationKey(Content content) {
        TextSegment seg = content.textSegment();
        String law = seg.metadata().getString("law_name");
        String article = seg.metadata().getString("article_number");
        if (law != null && article != null) {
            return law + ":" + article;
        }
        String text = seg.text();
        return text.substring(0, Math.min(100, text.length()));
    }

    /**
     * 调用 LLM 改写用户查询
     */
    public String rewrite(String originalQuery) {
        try {
            String prompt = String.format(REWRITE_PROMPT, originalQuery);
            String result = chatModel.chat(prompt).trim();
            if (result.isEmpty() || result.length() > originalQuery.length() * 3) {
                return originalQuery;
            }
            return result;
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }
}
