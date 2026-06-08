package org.example.consultant3.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.example.consultant3.aiservice.config.CommonConfig;
import org.example.consultant3.aiservice.graph.Neo4jGraphConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识图谱管理接口。
 * <p>
 * 提供图谱的构建、查询和调试功能。
 */
@RestController
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);

    @Autowired
    private CommonConfig commonConfig;

    @Autowired
    private Neo4jGraphConfig neo4jGraph;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String dashscopeApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String dashscopeBaseUrl;

    private static final Path GRAPH_JSON_PATH =
            Paths.get("src/main/resources/graph/legal-graph-relations.json");

    private static final String EXTRACT_PROMPT = """
            你是一个法律知识图谱构建助手。我会给你一部法律的名称和其中若干法条的条号+内容。

            请为每条法条标注：
            1. caseTypes：该法条适用的案由类型（如"工伤赔偿""合同纠纷""交通事故""离婚纠纷"等）。如果该法条不直接对应案由，可以为空数组。
            2. concepts：该法条涉及的法律概念（如"违约责任""试用期""诉讼时效""精神损害赔偿"等）

            请以 JSON 格式返回，格式严格如下：
            {
              "articles": [
                {
                  "articleNumber": "第一百四十八条",
                  "caseTypes": ["合同纠纷"],
                  "concepts": ["欺诈", "可撤销合同", "撤销权"]
                }
              ]
            }

            只输出 JSON，不要解释。

            法律名称：%s

            法条列表：
            %s
            """;

    /**
     * 一次性构建图谱的语义关系（案由和概念）。
     * <p>
     * 用 LLM 给法条打标，结果缓存到 JSON 文件，然后灌入 Neo4j。
     * 这是一个手动触发的离线任务，不会在启动时自动执行。
     * <p>
     * 用法: GET /test/build-graph
     *       GET /test/build-graph?law=民法典       （只处理指定法律）
     *       GET /test/build-graph?limit=20         （限制处理法条数，用于测试）
     */
    @GetMapping(value = "/test/build-graph", produces = "application/json;charset=utf-8")
    public Map<String, Object> buildGraphRelations(
            @RequestParam(required = false) String law,
            @RequestParam(required = false, defaultValue = "0") int limit) throws Exception {

        Map<String, Object> result = new LinkedHashMap<>();
        long startTime = System.currentTimeMillis();

        List<TextSegment> allSegments = commonConfig.getAllSegments();

        // 按法律名称分组
        Map<String, List<TextSegment>> lawGroups = new LinkedHashMap<>();
        for (TextSegment seg : allSegments) {
            String lawName = seg.metadata().getString("law_name");
            String articleNum = seg.metadata().getString("article_number");
            if (lawName == null || articleNum == null) continue;

            if (law != null && !lawName.contains(law)) continue;

            lawGroups.computeIfAbsent(lawName, k -> new ArrayList<>()).add(seg);
        }

        result.put("total_laws", lawGroups.size());
        result.put("total_articles", lawGroups.values().stream().mapToInt(List::size).sum());

        // 加载已有缓存（增量模式）
        Map<String, List<Map<String, Object>>> existingRelations = loadExistingRelations();

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(dashscopeBaseUrl)
                .apiKey(dashscopeApiKey)
                .modelName("qwen-plus")
                .temperature(0.1)
                .timeout(Duration.ofSeconds(60))
                .build();

        int processedArticles = 0;
        int totalRelations = 0;
        List<Map<String, Object>> allResults = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, List<TextSegment>> entry : lawGroups.entrySet()) {
            String lawName = entry.getKey();
            List<TextSegment> segments = entry.getValue();

            // 跳过已缓存的
            if (existingRelations.containsKey(lawName)) {
                allResults.addAll(existingRelations.get(lawName));
                continue;
            }

            // 分批处理（每部法律一次最多 15 条，避免 prompt 过长）
            int batchSize = 15;
            for (int i = 0; i < segments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, segments.size());
                List<TextSegment> batch = segments.subList(i, end);

                StringBuilder articlesText = new StringBuilder();
                for (TextSegment seg : batch) {
                    String articleNum = seg.metadata().getString("article_number");
                    String text = seg.text();
                    // 截断长文本
                    if (text.length() > 400) text = text.substring(0, 400);
                    articlesText.append("条号：").append(articleNum).append("\n")
                            .append("内容：").append(text).append("\n\n");
                }

                String prompt = String.format(EXTRACT_PROMPT, lawName, articlesText.toString());

                try {
                    String response = model.chat(prompt).trim();
                    // 清理可能的 markdown 代码块包裹
                    if (response.startsWith("```")) {
                        response = response.replaceAll("```json\\s*", "")
                                .replaceAll("```\\s*", "").trim();
                    }

                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> parsed = mapper.readValue(response,
                            new TypeReference<Map<String, Object>>() {});

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> articles = (List<Map<String, Object>>) parsed.get("articles");
                    if (articles != null) {
                        for (Map<String, Object> article : articles) {
                            article.put("lawName", lawName);
                            allResults.add(article);
                        }
                        processedArticles += articles.size();
                        totalRelations += articles.stream()
                                .mapToInt(a -> {
                                    List<?> ct = (List<?>) a.getOrDefault("caseTypes", List.of());
                                    List<?> cp = (List<?>) a.getOrDefault("concepts", List.of());
                                    return ct.size() + cp.size();
                                }).sum();
                    }
                } catch (Exception e) {
                    String errMsg = lawName + " batch " + (i / batchSize) + ": " + e.getMessage();
                    log.error("图谱抽取失败: {}", errMsg);
                    errors.add(errMsg);
                }

                if (limit > 0 && processedArticles >= limit) break;
            }
            if (limit > 0 && processedArticles >= limit) break;
        }

        // 保存到 JSON 文件
        saveRelations(allResults);

        result.put("processed_articles", processedArticles);
        result.put("total_relations", totalRelations);
        result.put("errors", errors);
        result.put("cache_file", GRAPH_JSON_PATH.toString());
        result.put("time_ms", System.currentTimeMillis() - startTime);

        return result;
    }

    /**
     * 将已有的案由/概念关系灌入 Neo4j。
     * <p>
     * 用法: GET /test/load-graph-relations
     */
    @GetMapping(value = "/test/load-graph-relations", produces = "application/json;charset=utf-8")
    public Map<String, Object> loadGraphRelations() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> relations = loadExistingRelations();

        int caseTypeCount = 0;
        int conceptCount = 0;

        for (Map.Entry<String, List<Map<String, Object>>> entry : relations.entrySet()) {
            String lawName = entry.getKey();
            for (Map<String, Object> article : entry.getValue()) {
                String articleNumber = (String) article.get("articleNumber");

                @SuppressWarnings("unchecked")
                List<String> caseTypes = (List<String>) article.getOrDefault("caseTypes", List.of());
                @SuppressWarnings("unchecked")
                List<String> concepts = (List<String>) article.getOrDefault("concepts", List.of());

                for (String ct : caseTypes) {
                    neo4jGraph.query("""
                            MERGE (c:CaseType {name: $name})
                            WITH c
                            MATCH (a:Article {lawName: $lawName, articleNumber: $articleNumber})
                            MERGE (c)-[:APPLIES]->(a)
                            """,
                            Map.of("name", ct, "lawName", lawName, "articleNumber", articleNumber));
                    caseTypeCount++;
                }

                for (String cp : concepts) {
                    neo4jGraph.query("""
                            MERGE (c:Concept {name: $name})
                            WITH c
                            MATCH (a:Article {lawName: $lawName, articleNumber: $articleNumber})
                            MERGE (c)-[:RELATES]->(a)
                            """,
                            Map.of("name", cp, "lawName", lawName, "articleNumber", articleNumber));
                    conceptCount++;
                }
            }
        }

        result.put("case_type_edges", caseTypeCount);
        result.put("concept_edges", conceptCount);
        result.put("status", "ok");
        return result;
    }

    /**
     * 调试接口：直接执行 Cypher 查询
     * <p>
     * 用法: GET /test/graph-query?cypher=MATCH (n) RETURN count(n)
     */
    @GetMapping(value = "/test/graph-query", produces = "application/json;charset=utf-8")
    public Map<String, Object> graphQuery(@RequestParam String cypher) {
        List<Map<String, Object>> rows = neo4jGraph.query(cypher, Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cypher", cypher);
        result.put("row_count", rows.size());
        result.put("rows", rows);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> loadExistingRelations() {
        if (!Files.exists(GRAPH_JSON_PATH)) {
            return Map.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> list = mapper.readValue(
                    GRAPH_JSON_PATH.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {});
            return list.stream()
                    .collect(Collectors.groupingBy(
                            m -> (String) m.get("lawName"),
                            LinkedHashMap::new,
                            Collectors.toList()));
        } catch (IOException e) {
            log.warn("加载图谱关系缓存失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private void saveRelations(List<Map<String, Object>> relations) {
        try {
            Files.createDirectories(GRAPH_JSON_PATH.getParent());
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(GRAPH_JSON_PATH.toFile(), relations);
            log.info("图谱关系已保存到 {}", GRAPH_JSON_PATH);
        } catch (IOException e) {
            log.error("保存图谱关系失败: {}", e.getMessage());
        }
    }
}
