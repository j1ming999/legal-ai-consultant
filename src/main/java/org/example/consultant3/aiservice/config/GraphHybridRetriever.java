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
 * 图谱增强混合检索器：向量初筛 → 2-hop 图谱引用链 → 加权排序。
 * <p>
 * 流程（一次性，无需 LLM 参与决策）：
 * <ol>
 *   <li>向量检索 Top-10 种子法条（语义匹配，~200ms）</li>
 *   <li>Neo4j 追踪 2-hop 引用链：REFERS_TO → REFERS_TO 和 CROSS_REFERS_TO → REFERS_TO</li>
 *   <li>加权合并：向量位置分 + 引用次数分，高被引法条插队提权</li>
 * </ol>
 * <p>
 * 向量负责初始命中，图谱负责关系补全。总耗时约 280ms。
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

        // 记录每个法条的分数和位置
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Content> items = new LinkedHashMap<>();

        // 向量结果：分数 = 20 - rank（越靠前分越高）
        for (int i = 0; i < vectorResults.size(); i++) {
            Content c = vectorResults.get(i);
            String key = deduplicationKey(c);
            if (key == null) continue;
            items.putIfAbsent(key, c);
            scores.merge(key, 20.0 - i, Math::max);
        }

        // 取前 10 条作为图谱种子
        List<Content> seeds = vectorResults.subList(0, Math.min(10, vectorResults.size()));

        // === 阶段2：图谱追 2-hop 引用链 ===
        try {
            // 2-hop: 种子 → REFERS_TO → 引用法条 → REFERS_TO → 深度法条
            //       种子 → CROSS_REFERS_TO → 跨法律法条 → REFERS_TO → 跨法律引用链
            String cypher = """
                    UNWIND $seeds AS seed
                    MATCH (a:Article {lawName: seed.law, articleNumber: seed.article})
                    OPTIONAL MATCH (a)-[:REFERS_TO]->(hop1:Article)
                    OPTIONAL MATCH (hop1)-[:REFERS_TO]->(hop2:Article)
                    OPTIONAL MATCH (a)-[:CROSS_REFERS_TO]->(cross1:Article)
                    OPTIONAL MATCH (cross1)-[:REFERS_TO]->(cross2:Article)
                    RETURN a.lawName AS law, a.articleNumber AS article,
                           COLLECT(DISTINCT {law: hop1.lawName, art: hop1.articleNumber, text: hop1.text}) AS hop1Refs,
                           COLLECT(DISTINCT {law: hop2.lawName, art: hop2.articleNumber, text: hop2.text}) AS hop2Refs,
                           COLLECT(DISTINCT {law: cross1.lawName, art: cross1.articleNumber, text: cross1.text}) AS cross1Refs,
                           COLLECT(DISTINCT {law: cross2.lawName, art: cross2.articleNumber, text: cross2.text}) AS cross2Refs
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

                // 统计每条法条被多少个种子引用（跨所有 hop）
                Map<String, Integer> refCounts = new HashMap<>();

                for (Map<String, Object> row : rows) {
                    countRefs(row, "hop1Refs", refCounts, items);
                    countRefs(row, "hop2Refs", refCounts, items);
                    countRefs(row, "cross1Refs", refCounts, items);
                    countRefs(row, "cross2Refs", refCounts, items);
                }

                // 引用次数加权：每被一个种子引用 +2 分
                for (Map.Entry<String, Integer> e : refCounts.entrySet()) {
                    scores.merge(e.getKey(), e.getValue() * 2.0, Double::sum);
                }
            }
        } catch (Exception e) {
            log.warn("图谱引用链查询失败: {}", e.getMessage());
        }

        // === 阶段3：按综合分数排序 ===
        List<Map.Entry<String, Content>> sorted = new ArrayList<>();
        for (Map.Entry<String, Content> e : items.entrySet()) {
            sorted.add(e);
        }
        sorted.sort((a, b) -> {
            double sa = scores.getOrDefault(a.getKey(), 0.0);
            double sb = scores.getOrDefault(b.getKey(), 0.0);
            return Double.compare(sb, sa); // 降序
        });

        return sorted.stream()
                .map(Map.Entry::getValue)
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /** 统计引用链中每条法条的出现次数，同时把新发现的法条缓存到 items */
    @SuppressWarnings("unchecked")
    private void countRefs(Map<String, Object> row, String field,
                           Map<String, Integer> refCounts, Map<String, Content> items) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) row.get(field);
        if (list == null) return;
        for (Map<String, Object> r : list) {
            Object law = r.get("law");
            Object art = r.get("art");
            if (law == null || art == null) continue;
            String key = law + ":" + art;
            refCounts.merge(key, 1, Integer::sum);
            // 缓存到 items（如果还不存在）
            if (!items.containsKey(key)) {
                String text = r.get("text") != null ? r.get("text").toString() : "";
                dev.langchain4j.data.document.Metadata meta =
                        new dev.langchain4j.data.document.Metadata();
                meta.put("law_name", law.toString());
                meta.put("article_number", art.toString());
                TextSegment seg = TextSegment.from(text, meta);
                items.put(key, Content.from(seg));
            }
        }
    }

}
