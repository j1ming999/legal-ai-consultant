package org.example.consultant3.aiservice.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class CommonConfig {

    private static final Logger log = LoggerFactory.getLogger(CommonConfig.class);

    /**
     * 修改此版本号可强制重新导入文档（如新增/修改了法律文件）。
     * 版本号存储在 Redis key "legal:embedding:version" 中，
     * 启动时比对，一致则跳过导入。
     */
    private static final String EMBEDDING_VERSION = "v2";
    private static final String VERSION_KEY = "legal:embedding:version";

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String dashscopeApiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String dashscopeBaseUrl;

    @Value("${app.content.dir:src/main/resources/content}")
    private String contentDir;

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return chatId -> MessageWindowChatMemory.withMaxMessages(10);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(dashscopeBaseUrl)
                .apiKey(dashscopeApiKey)
                .modelName("text-embedding-v3")
                .build();
    }

    @Bean
    public RedisEmbeddingStore redisEmbeddingStore() {
        return RedisEmbeddingStore.builder()
                .host(redisHost)
                .port(redisPort)
                .dimension(1024)
                .indexName("legal-embeddings")
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(
            RedisEmbeddingStore redisEmbeddingStore,
            EmbeddingModel embeddingModel,
            StringRedisTemplate redisTemplate
    ) {
        // 始终解析文档（本地操作，无 API 调用，很快）
        List<TextSegment> allSegments = parseDocuments();

        // 版本检查：避免每次启动重复 embedding
        String storedVersion = redisTemplate.opsForValue().get(VERSION_KEY);
        if (!EMBEDDING_VERSION.equals(storedVersion)) {
            embedAndStore(allSegments, redisEmbeddingStore, embeddingModel);
            redisTemplate.opsForValue().set(VERSION_KEY, EMBEDDING_VERSION);
        } else {
            log.info("法律文档向量库已是最新版本({}), 跳过导入", EMBEDDING_VERSION);
        }

        // 向量检索器（同时注册为 Bean 供测试对比使用）
        this.vectorOnlyRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(redisEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(20)
                .minScore(0.1)
                .build();

        // 关键词检索器
        this.keywordOnlyRetriever = new KeywordContentRetriever(allSegments, 20);

        // 缓存片段供测试接口使用
        this.allSegments = allSegments;

        // 混合检索：加权RRF融合（向量权重1.0，关键词权重1.0 等权融合），返回 Top-20
        this.hybridRetriever = new HybridContentRetriever(vectorOnlyRetriever, keywordOnlyRetriever, 20, 1.0, 1.0);
        log.info("混合检索器就绪：向量检索(×1.0) + 关键词检索(×1.0) 等权RRF融合");

        // 查询改写 + 混合检索
        ChatModel rewriteModel = OpenAiChatModel.builder()
                .baseUrl(dashscopeBaseUrl)
                .apiKey(dashscopeApiKey)
                .modelName("qwen-plus")
                .timeout(java.time.Duration.ofSeconds(15))
                .build();
        this.rewriteRetriever = new QueryRewriteRetriever(hybridRetriever, rewriteModel);
        log.info("查询改写检索器就绪");

        return rewriteRetriever;
    }

    // 暴露给测试接口使用
    private ContentRetriever vectorOnlyRetriever;
    private KeywordContentRetriever keywordOnlyRetriever;
    private ContentRetriever hybridRetriever;
    private QueryRewriteRetriever rewriteRetriever;
    private List<TextSegment> allSegments;

    public ContentRetriever getVectorOnlyRetriever() { return vectorOnlyRetriever; }
    public KeywordContentRetriever getKeywordOnlyRetriever() { return keywordOnlyRetriever; }
    public ContentRetriever getHybridRetriever() { return hybridRetriever; }
    public QueryRewriteRetriever getRewriteRetriever() { return rewriteRetriever; }
    public List<TextSegment> getAllSegments() { return allSegments; }

    /**
     * 解析所有法律文档为 TextSegment（纯本地操作，不调用 API）
     */
    private List<TextSegment> parseDocuments() {
        Path contentPath = Paths.get(contentDir);
        // 使用 Apache Tika 解析器，支持 .txt / .docx / .pdf 等格式
        List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively(
                contentPath, new ApacheTikaDocumentParser());
        log.info("加载了 {} 个法律文档", documents.size());

        LegalDocumentSplitter splitter = new LegalDocumentSplitter();
        List<TextSegment> allSegments = new ArrayList<>();
        for (Document doc : documents) {
            List<TextSegment> segs = splitter.split(doc);
            log.debug("  {} -> {} 个片段", doc.metadata().getString("file_name"), segs.size());
            allSegments.addAll(segs);
        }
        log.info("共切分为 {} 个法条片段", allSegments.size());
        return allSegments;
    }

    /**
     * 将文本片段批量 embedding 并存入 Redis 向量库
     */
    private void embedAndStore(List<TextSegment> segments,
                               RedisEmbeddingStore store,
                               EmbeddingModel embeddingModel) {
        log.info("开始导入法律文档到向量库...");
        int batchSize = 10;
        for (int i = 0; i < segments.size(); i += batchSize) {
            List<TextSegment> batch = segments.subList(i, Math.min(i + batchSize, segments.size()));
            List<Embedding> embeddings = embeddingModel.embedAll(batch).content();
            store.addAll(embeddings, batch);
        }
        log.info("法律文档导入完成");
    }
}
