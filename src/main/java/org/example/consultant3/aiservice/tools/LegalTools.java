package org.example.consultant3.aiservice.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.example.consultant3.aiservice.config.CommonConfig;
import org.example.consultant3.aiservice.graph.Neo4jGraphConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 工具集：给 LLM 提供三种法律检索能力。
 * <p>
 * LLM 会根据用户问题的性质自主选择调用哪个工具、调用几次：
 * <ul>
 *   <li>口语化/概念性问题 → vectorSearch</li>
 *   <li>需要了解法条间引用关系 → graphQuery</li>
 *   <li>按法名+条号精确查找 → exactArticle</li>
 * </ul>
 */
@Component("legalTools")
public class LegalTools {

    private static final Logger log = LoggerFactory.getLogger(LegalTools.class);

    private final ContentRetriever hybridRetriever;
    private final Neo4jGraphConfig neo4jGraph;

    public LegalTools(CommonConfig commonConfig, Neo4jGraphConfig neo4jGraph) {
        this.hybridRetriever = commonConfig.getHybridRetriever();
        this.neo4jGraph = neo4jGraph;
    }

    /**
     * 语义检索：根据自然语言查询检索相关法条。
     * 适合用户用口语描述法律问题、不记得具体法条编号的场景。
     */
    @Tool("根据自然语言描述的法律问题，语义检索最相关的法条原文。适合口语化问题，如'老板拖欠工资怎么办'、'交通事故怎么赔偿'")
    public String vectorSearch(String query) {
        log.info("[Tool] vectorSearch: {}", query);
        try {
            List<Content> results = hybridRetriever.retrieve(Query.from(query));
            if (results.isEmpty()) {
                return "未找到相关法条。";
            }
            return formatResults(results, Math.min(results.size(), 10));
        } catch (Exception e) {
            log.error("vectorSearch 失败: {}", e.getMessage());
            return "检索失败：" + e.getMessage();
        }
    }

    /**
     * 图谱多跳查询：从案由或法律概念出发，找到关联法条及其引用链。
     * 适合需要追溯法律依据链条的复杂问题。
     */
    @Tool("查询法律知识图谱，从案由（如'工伤赔偿'）或法律概念（如'违约责任'）出发，找到关联法条以及它们引用的其他法条。支持多跳推理，适合复杂法律推理问题")
    public String graphQuery(String caseTypeOrConcept) {
        log.info("[Tool] graphQuery: {}", caseTypeOrConcept);
        try {
            // 先找直接关联的 Article 节点（按概念/案由名模糊匹配）
            String cypher = """
                    MATCH (a:Article)
                    WHERE a.lawName CONTAINS $keyword
                       OR a.chapter CONTAINS $keyword
                       OR a.text CONTAINS $keyword
                    WITH a
                    OPTIONAL MATCH (a)-[:REFERS_TO]->(ref:Article)
                    RETURN a.lawName AS law, a.articleNumber AS article,
                           a.chapter AS chapter, a.text AS text,
                           COLLECT(DISTINCT {law: ref.lawName, article: ref.articleNumber}) AS references
                    LIMIT 15
                    """;

            List<Map<String, Object>> rows = neo4jGraph.query(cypher, Map.of("keyword", caseTypeOrConcept));

            if (rows.isEmpty()) {
                return "图谱中未找到与\"" + caseTypeOrConcept + "\"相关的法条。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("图谱查询结果（关键词：").append(caseTypeOrConcept).append("）：\n\n");
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                sb.append(i + 1).append(". 《").append(row.get("law")).append("》")
                        .append(" ").append(row.get("article")).append("\n");
                Object chapter = row.get("chapter");
                if (chapter != null && !chapter.toString().isEmpty()) {
                    sb.append("   章节：").append(chapter).append("\n");
                }
                Object text = row.get("text");
                if (text != null) {
                    sb.append("   ").append(text).append("\n");
                }
                // 显示引用关系
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> refs = (List<Map<String, Object>>) row.get("references");
                if (refs != null && !refs.isEmpty()) {
                    sb.append("   引用法条：");
                    sb.append(refs.stream()
                            .map(r -> "《" + r.get("law") + "》" + r.get("article"))
                            .collect(Collectors.joining("、")));
                    sb.append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("graphQuery 失败: {}", e.getMessage());
            return "图谱查询失败：" + e.getMessage();
        }
    }

    /**
     * 精确法条查询：按法律名称和条号精确查找。
     * 适合用户明确提到"某法第几条"的场景。
     */
    @Tool("按法律名称和条号精确查询法条原文。当用户提到具体法条（如'民法典第148条'）时使用。lawName 为法律全称，articleNumber 为条号（如'第一百四十八条'）")
    public String exactArticle(String lawName, String articleNumber) {
        log.info("[Tool] exactArticle: {} {}", lawName, articleNumber);
        try {
            String cypher = """
                    MATCH (a:Article {lawName: $lawName, articleNumber: $articleNumber})
                    RETURN a.lawName AS law, a.articleNumber AS article,
                           a.chapter AS chapter, a.text AS text
                    """;

            List<Map<String, Object>> rows = neo4jGraph.query(cypher,
                    Map.of("lawName", lawName, "articleNumber", articleNumber));

            if (rows.isEmpty()) {
                // 退路：如果精确匹配失败，尝试模糊匹配
                cypher = """
                        MATCH (a:Article)
                        WHERE a.lawName CONTAINS $lawName
                          AND a.articleNumber CONTAINS $articleNumber
                        RETURN a.lawName AS law, a.articleNumber AS article,
                               a.chapter AS chapter, a.text AS text
                        LIMIT 5
                        """;
                rows = neo4jGraph.query(cypher,
                        Map.of("lawName", lawName, "articleNumber", articleNumber));

                if (rows.isEmpty()) {
                    return "未找到《" + lawName + "》" + articleNumber + "的原文。";
                }
            }

            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                sb.append("《").append(row.get("law")).append("》")
                        .append(" ").append(row.get("article")).append("\n");
                Object chapter = row.get("chapter");
                if (chapter != null && !chapter.toString().isEmpty()) {
                    sb.append("章节：").append(chapter).append("\n");
                }
                Object text = row.get("text");
                if (text != null) {
                    sb.append(text.toString()).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("exactArticle 失败: {}", e.getMessage());
            return "精确查询失败：" + e.getMessage();
        }
    }

    private String formatResults(List<Content> contents, int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索到以下相关法条：\n\n");
        int count = 0;
        for (Content c : contents) {
            if (count >= limit) break;
            String law = c.textSegment().metadata().getString("law_name");
            String article = c.textSegment().metadata().getString("article_number");
            String text = c.textSegment().text();

            // 截断过长文本
            if (text.length() > 300) {
                text = text.substring(0, 300) + "...";
            }

            sb.append(++count).append(". ");
            if (law != null) {
                sb.append("《").append(law).append("》");
            }
            if (article != null) {
                sb.append(" ").append(article);
            }
            sb.append("\n").append(text).append("\n\n");
        }
        return sb.toString();
    }
}
