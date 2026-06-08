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
    private static final String GRAPH_VERSION = "v2";
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

            // 4. 创建 REFERS_TO 引用边（含跨法律引用）
            int edgeCount = createReferenceEdges(session, segments);
            log.info("  已创建 {} 条 REFERS_TO 边", edgeCount);

            // 5. 创建 CROSS_REFERS_TO 跨法律引用边
            int crossEdgeCount = createCrossLawReferenceEdges(session, segments);
            log.info("  已创建 {} 条 CROSS_REFERS_TO 跨法律引用边", crossEdgeCount);
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
     * 创建跨法律引用边：源法条 → 其他法律的被引用法条。
     * <p>
     * 匹配《XX法》第Y条 模式，目标法律名做模糊匹配
     * （因为引用中用的是简称如"劳动法"，但节点里存的是全称"中华人民共和国劳动法"）。
     */
    private int createCrossLawReferenceEdges(Session session, List<TextSegment> segments) {
        ArticleReferenceExtractor extractor = new ArticleReferenceExtractor();
        int edgeCount = 0;

        // 先收集所有法律全称，做简称→全称的模糊映射
        Set<String> allLawNames = new LinkedHashSet<>();
        for (TextSegment seg : segments) {
            String name = seg.metadata().getString("law_name");
            if (name != null) allLawNames.add(name);
        }

        for (TextSegment seg : segments) {
            String srcLaw = seg.metadata().getString("law_name");
            String srcArticle = seg.metadata().getString("article_number");
            if (srcLaw == null || srcArticle == null) continue;

            Set<ArticleReferenceExtractor.CrossLawRef> crossRefs =
                    extractor.extractCrossLawReferences(seg.text());
            if (crossRefs.isEmpty()) continue;

            for (ArticleReferenceExtractor.CrossLawRef ref : crossRefs) {
                // 排除自引用（同一法律）
                if (srcLaw.equals(ref.targetLawName) || srcLaw.contains(ref.targetLawName)
                        || (ref.targetLawName.length() >= 4 && srcLaw.contains(ref.targetLawName))) {
                    continue;
                }

                // 用简称模糊匹配找到目标法律的完整名称
                String matchedLaw = findLawByName(allLawNames, ref.targetLawName);
                if (matchedLaw == null) {
                    continue; // 目标法律不在知识库中，跳过
                }

                session.run(
                        "MATCH (src:Article {lawName: $srcLaw, articleNumber: $srcArticle}) " +
                        "MATCH (tgt:Article {lawName: $tgtLaw, articleNumber: $tgtArticle}) " +
                        "MERGE (src)-[:CROSS_REFERS_TO]->(tgt)",
                        Values.parameters(
                                "srcLaw", srcLaw,
                                "srcArticle", srcArticle,
                                "tgtLaw", matchedLaw,
                                "tgtArticle", ref.targetArticle
                        )
                );
                edgeCount++;
            }
        }
        return edgeCount;
    }

    /**
     * 在已有的法律名称集合中模糊匹配。
     * 引用时常用简称（如"劳动合同法"），节点里是完整名称（如"中华人民共和国劳动合同法"）。
     */
    private String findLawByName(Set<String> allLawNames, String shortName) {
        // 精确匹配
        if (allLawNames.contains(shortName)) {
            return shortName;
        }
        // contains 匹配：全称包含简称
        for (String fullName : allLawNames) {
            if (fullName.contains(shortName)) {
                return fullName;
            }
        }
        // 反向匹配：简称包含在全称中（如"《民法典》"匹配"中华人民共和国民法典"）
        for (String fullName : allLawNames) {
            if (shortName.length() >= 3 && fullName.contains(shortName.replace("《", "").replace("》", ""))) {
                return fullName;
            }
        }
        return null;
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
