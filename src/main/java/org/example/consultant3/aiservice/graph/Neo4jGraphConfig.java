package org.example.consultant3.aiservice.graph;

import dev.langchain4j.data.segment.TextSegment;
import org.neo4j.driver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.PreDestroy;
import java.util.*;

/**
 * Neo4j 知识图谱配置与构建器。
 * <p>
 * 职责：
 * <ol>
 *   <li>提供 Neo4j {@link Driver} Bean（单例连接池）</li>
 *   <li>启动时检查图谱版本，若不一致则重建</li>
 *   <li>从法条 TextSegment 构建 Article 节点和 REFERS_TO 引用边</li>
 * </ol>
 * <p>
 * 版本控制沿用与向量库相同的思路：Redis 存版本号，改变 {@code GRAPH_VERSION}
 * 常量即可触发全量重建。
 */
@Configuration
public class Neo4jGraphConfig {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphConfig.class);

    /** 修改此版本号可强制重建图谱 */
    private static final String GRAPH_VERSION = "v1";
    private static final String VERSION_KEY = "legal:graph:version";

    @Value("${neo4j.uri:bolt://localhost:7687}")
    private String neo4jUri;

    @Value("${neo4j.username:neo4j}")
    private String neo4jUsername;

    @Value("${neo4j.password}")
    private String neo4jPassword;

    private Driver driver;

    @Bean
    public Driver neo4jDriver() {
        this.driver = GraphDatabase.driver(
                neo4jUri,
                AuthTokens.basic(neo4jUsername, neo4jPassword),
                Config.builder()
                        .withMaxConnectionPoolSize(10)
                        .withConnectionAcquisitionTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
        );
        log.info("Neo4j driver 已创建: {}", neo4jUri);
        return driver;
    }

    @PreDestroy
    public void closeDriver() {
        if (driver != null) {
            driver.close();
            log.info("Neo4j driver 已关闭");
        }
    }

    /**
     * 构建知识图谱（启动时由 CommonConfig 调用）。
     * <p>
     * 步骤：
     * <ol>
     *   <li>检查 Redis 里 legal:graph:version 是否与 GRAPH_VERSION 一致</li>
     *   <li>不一致则清空图、建 Article 节点、建 REFERS_TO 边</li>
     *   <li>更新版本号</li>
     * </ol>
     *
     * @param segments      所有法条 TextSegment（来自 CommonConfig.parseDocuments）
     * @param redisTemplate 用于版本号存储
     */
    public void buildGraphIfNeeded(List<TextSegment> segments, StringRedisTemplate redisTemplate) {
        String storedVersion = redisTemplate.opsForValue().get(VERSION_KEY);
        if (GRAPH_VERSION.equals(storedVersion)) {
            log.info("法律知识图谱已是最新版本({}), 跳过构建", GRAPH_VERSION);
            return;
        }

        log.info("开始构建法律知识图谱(版本 {})...", GRAPH_VERSION);
        long start = System.currentTimeMillis();

        try (Session session = driver.session()) {
            // 1. 清空图
            session.run("MATCH (n) DETACH DELETE n");
            log.info("  已清空旧图谱");

            // 2. 建唯一性约束（幂等）
            session.run("CREATE CONSTRAINT IF NOT EXISTS FOR (a:Article) REQUIRE (a.lawName, a.articleNumber) IS UNIQUE");

            // 3. 批量创建 Article 节点
            int nodeCount = createArticleNodes(session, segments);
            log.info("  已创建 {} 个 Article 节点", nodeCount);

            // 4. 创建 REFERS_TO 引用边
            int edgeCount = createReferenceEdges(session, segments);
            log.info("  已创建 {} 条 REFERS_TO 边", edgeCount);
        }

        redisTemplate.opsForValue().set(VERSION_KEY, GRAPH_VERSION);
        long cost = System.currentTimeMillis() - start;
        log.info("法律知识图谱构建完成, 耗时 {}ms", cost);
    }

    /**
     * 批量创建 Article 节点（每批 100 条，减少事务开销）
     */
    private int createArticleNodes(Session session, List<TextSegment> segments) {
        int batchSize = 100;
        int count = 0;

        for (int i = 0; i < segments.size(); i += batchSize) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + batchSize, segments.size()));
            List<Map<String, Object>> paramList = new ArrayList<>();

            for (TextSegment seg : batch) {
                String lawName = seg.metadata().getString("law_name");
                String articleNumber = seg.metadata().getString("article_number");
                String chapter = seg.metadata().getString("chapter");

                if (lawName == null || articleNumber == null) {
                    continue;
                }

                Map<String, Object> params = new HashMap<>();
                params.put("lawName", lawName);
                params.put("articleNumber", articleNumber);
                params.put("chapter", chapter != null ? chapter : "");
                // 截断文本避免单个节点过大（图谱节点不需要完整原文，原文在向量库里已有）
                String text = seg.text();
                params.put("text", text.length() > 500 ? text.substring(0, 500) : text);
                paramList.add(params);
            }

            if (!paramList.isEmpty()) {
                session.run(
                        "UNWIND $nodes AS n " +
                        "MERGE (a:Article {lawName: n.lawName, articleNumber: n.articleNumber}) " +
                        "SET a.chapter = n.chapter, a.text = n.text",
                        Values.parameters("nodes", paramList)
                );
                count += paramList.size();
            }
        }
        return count;
    }

    /**
     * 创建 REFERS_TO 引用边：同一部法律内法条之间的引用关系
     */
    private int createReferenceEdges(Session session, List<TextSegment> segments) {
        ArticleReferenceExtractor extractor = new ArticleReferenceExtractor();
        int edgeCount = 0;

        for (TextSegment seg : segments) {
            String lawName = seg.metadata().getString("law_name");
            String selfArticle = seg.metadata().getString("article_number");
            if (lawName == null || selfArticle == null) {
                continue;
            }

            Set<String> refs = extractor.extractReferences(seg.text(), selfArticle);
            if (refs.isEmpty()) {
                continue;
            }

            for (String targetArticle : refs) {
                session.run(
                        "MATCH (src:Article {lawName: $lawName, articleNumber: $srcArticle}) " +
                        "MATCH (tgt:Article {lawName: $lawName, articleNumber: $tgtArticle}) " +
                        "MERGE (src)-[:REFERS_TO]->(tgt)",
                        Values.parameters(
                                "lawName", lawName,
                                "srcArticle", selfArticle,
                                "tgtArticle", targetArticle
                        )
                );
                edgeCount++;
            }
        }
        return edgeCount;
    }

    /**
     * 执行 Cypher 查询并返回结果（供 LegalTools 和调试接口使用）
     */
    public List<Map<String, Object>> query(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, params);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                Map<String, Object> row = new HashMap<>();
                for (String key : record.keys()) {
                    row.put(key, record.get(key).asObject());
                }
                rows.add(row);
            }
            return rows;
        }
    }
}
