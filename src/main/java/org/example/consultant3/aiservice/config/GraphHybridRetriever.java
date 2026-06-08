package org.example.consultant3.aiservice.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.example.consultant3.aiservice.graph.Neo4jGraphConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 图谱增强混合检索器：单次检索完成图谱定位 + 语义匹配，替代 Agent 多轮工具调用。
 * <p>
 * 流程（一次性，无需 LLM 参与决策）：
 * <ol>
 *   <li>Neo4j 图谱查询：关键词匹配 Article 节点，追踪 REFERS_TO 和 CROSS_REFERS_TO 引用链</li>
 *   <li>Redis 向量语义检索：利用现有混合检索器补充语义相关法条</li>
 *   <li>合并去重：图谱结果优先（结构性相关），向量结果补充（语义相关）</li>
 * </ol>
 * <p>
 * 总耗时约 300-500ms，无 LLM 往返开销，适合 3 秒以内的在线响应要求。
 */
public class GraphHybridRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(GraphHybridRetriever.class);

    private final ContentRetriever hybridRetriever;   // 向量+关键词 RRF
    private final Neo4jGraphConfig neo4jGraph;
    private final int maxResults;

    /** 去重键：lawName + articleNumber，与 HybridContentRetriever 一致 */
    private String deduplicationKey(Content content) {
        TextSegment seg = content.textSegment();
        String law = seg.metadata().getString("law_name");
        String article = seg.metadata().getString("article_number");
        if (law != null && article != null) return law + ":" + article;
        String text = seg.text();
        return text.substring(0, Math.min(100, text.length()));
    }

    public GraphHybridRetriever(ContentRetriever hybridRetriever,
                                Neo4jGraphConfig neo4jGraph,
                                int maxResults) {
        this.hybridRetriever = hybridRetriever;
        this.neo4jGraph = neo4jGraph;
        this.maxResults = maxResults;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query.text();
        Map<String, Content> merged = new LinkedHashMap<>();

        // === 阶段1：图谱检索（结构化引用链）===
        try {
            String cypher = """
                    MATCH (a:Article)
                    WHERE a.lawName CONTAINS $kw
                       OR a.chapter CONTAINS $kw
                       OR a.text CONTAINS $kw
                    WITH a
                    OPTIONAL MATCH (a)-[:REFERS_TO]->(ref:Article)
                    OPTIONAL MATCH (a)-[:CROSS_REFERS_TO]->(cross:Article)
                    RETURN a.lawName AS law, a.articleNumber AS article,
                           a.chapter AS chapter, a.text AS text,
                           COLLECT(DISTINCT {law: ref.lawName, art: ref.articleNumber, text: ref.text}) AS refs,
                           COLLECT(DISTINCT {law: cross.lawName, art: cross.articleNumber, text: cross.text}) AS crossRefs
                    LIMIT 15
                    """;

            List<Map<String, Object>> rows = neo4jGraph.query(cypher, Map.of("kw", queryText));

            for (Map<String, Object> row : rows) {
                // 直接命中节点
                addGraphResult(merged, row);
                // 同法律引用链
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> refs = (List<Map<String, Object>>) row.get("refs");
                if (refs != null) {
                    for (Map<String, Object> r : refs) {
                        addRefResult(merged, r, false);
                    }
                }
                // 跨法律引用链
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> crossRefs = (List<Map<String, Object>>) row.get("crossRefs");
                if (crossRefs != null) {
                    for (Map<String, Object> cr : crossRefs) {
                        addRefResult(merged, cr, true);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("图谱检索失败，降级到纯向量检索: {}", e.getMessage());
        }

        // === 阶段2：向量语义检索补充 ===
        List<Content> vectorResults = hybridRetriever.retrieve(query);
        for (Content c : vectorResults) {
            String key = deduplicationKey(c);
            // 不覆盖已有图谱结果（图谱优先）
            merged.putIfAbsent(key, c);
        }

        // === 返回 Top-N ===
        return merged.values().stream()
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /** 将图谱直接命中结果转为 Content */
    private void addGraphResult(Map<String, Content> merged, Map<String, Object> row) {
        Object law = row.get("law");
        Object article = row.get("article");
        if (law == null || article == null) return;

        String key = law + ":" + article;
        if (merged.containsKey(key)) return;

        String chapter = row.get("chapter") != null ? row.get("chapter").toString() : "";
        String text = row.get("text") != null ? row.get("text").toString() : "";

        dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
        meta.put("law_name", law.toString());
        meta.put("article_number", article.toString());
        meta.put("chapter", chapter);

        TextSegment seg = TextSegment.from(text, meta);
        merged.put(key, Content.from(seg));
    }

    /** 将引用链结果转为 Content */
    private void addRefResult(Map<String, Content> merged, Map<String, Object> row, boolean isCross) {
        Object law = row.get("law");
        Object article = row.get("art");
        if (law == null || article == null) return;

        String key = law + ":" + article;
        if (merged.containsKey(key)) return;

        String text = row.get("text") != null ? row.get("text").toString() : "";

        dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
        meta.put("law_name", law.toString());
        meta.put("article_number", article.toString());
        meta.put("chapter", isCross ? "[跨法律引用]" : "");

        TextSegment seg = TextSegment.from(text, meta);
        merged.put(key, Content.from(seg));
    }
}
