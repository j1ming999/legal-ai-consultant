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
 * 图谱增强混合检索器：向量初筛 → 图谱追引用链 → 合并。
 * <p>
 * 流程（一次性，无需 LLM 参与决策）：
 * <ol>
 *   <li>向量检索 Top-10 种子法条（语义匹配，~200ms）</li>
 *   <li>Neo4j 查这 10 条的 REFERS_TO + CROSS_REFERS_TO 引用链（~50ms）</li>
 *   <li>合并：向量结果在前，图谱引用链追加在后，按 lawName+articleNumber 去重</li>
 * </ol>
 * <p>
 * 向量负责初始命中，图谱负责关系补全。总耗时约 250ms。
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
        // === 阶段1：向量检索 Top-10 种子法条 ===
        List<Content> vectorResults = hybridRetriever.retrieve(query);
        if (vectorResults.isEmpty()) {
            return List.of();
        }

        Map<String, Content> merged = new LinkedHashMap<>();

        // 先把向量结果放入（保持向量排序）
        for (Content c : vectorResults) {
            String key = deduplicationKey(c);
            if (key != null) merged.putIfAbsent(key, c);
        }

        // 取前 10 条作为图谱种子
        List<Content> seeds = vectorResults.subList(0, Math.min(10, vectorResults.size()));

        // === 阶段2：图谱追引用链 ===
        try {
            String cypher = """
                    UNWIND $seeds AS seed
                    MATCH (a:Article {lawName: seed.law, articleNumber: seed.article})
                    OPTIONAL MATCH (a)-[:REFERS_TO]->(ref:Article)
                    OPTIONAL MATCH (a)-[:CROSS_REFERS_TO]->(cross:Article)
                    RETURN a.lawName AS law, a.articleNumber AS article,
                           a.chapter AS chapter, a.text AS text,
                           COLLECT(DISTINCT {law: ref.lawName, art: ref.articleNumber, text: ref.text}) AS refs,
                           COLLECT(DISTINCT {law: cross.lawName, art: cross.articleNumber, text: cross.text}) AS crossRefs
                    """;

            List<Map<String, Object>> seedParams = new ArrayList<>();
            for (Content seed : seeds) {
                TextSegment seg = seed.textSegment();
                String law = seg.metadata().getString("law_name");
                String article = seg.metadata().getString("article_number");
                if (law != null && article != null) {
                    seedParams.add(Map.of("law", law, "article", article));
                }
            }

            if (!seedParams.isEmpty()) {
                List<Map<String, Object>> rows = neo4jGraph.query(cypher,
                        Map.of("seeds", seedParams));

                for (Map<String, Object> row : rows) {
                    // 引用链结果追加到向量结果后面（图谱补充，不插队）
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> refs = (List<Map<String, Object>>) row.get("refs");
                    if (refs != null) {
                        for (Map<String, Object> r : refs) {
                            addRefResult(merged, r, false);
                        }
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> crossRefs = (List<Map<String, Object>>) row.get("crossRefs");
                    if (crossRefs != null) {
                        for (Map<String, Object> cr : crossRefs) {
                            addRefResult(merged, cr, true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("图谱引用链查询失败，使用纯向量结果: {}", e.getMessage());
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
