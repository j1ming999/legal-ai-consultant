# Agentic RAG + 知识图谱 改造方案

## 目标

把现在的"固定检索管道"升级为两件事:

1. **Agentic RAG** — LLM 通过 LangChain4J `@Tool` 函数调用,自主决定**检索不检索、检索几轮、用哪个工具**,而不是每个问题都强制走"改写→混合检索→生成"。
2. **知识图谱(Neo4j)** — 把法条之间的引用关系、案由→法条、概念→法条显式建模,支持纯向量做不到的**多跳推理**。

## 现状(已确认)

- `ConsultantService` 用 `@AiService(contentRetriever="contentRetriever")` 自动注入 RAG,LLM 被动接收检索结果。
- `contentRetriever` = `QueryRewriteRetriever` → `HybridContentRetriever` → 向量 + 关键词。每个问题都强制改写(~2.6s)。
- 法条已按"条"切分,带 `law_name / chapter / article_number` 元数据 —— 这是图谱节点的天然主键。
- 法条文本里"本法第X条"引用 10 个文件共 110 处(民法典 57 处),**可用正则零成本抽取**。
- `/test/*` 消融实验接口直接依赖 `CommonConfig` 的各 retriever bean —— **这是简历亮点,改造必须保留它能跑**。

## 设计决策(已与你确认)

- 图存储:**Neo4j**(Docker 容器 + Cypher 多跳)
- Agent 方式:**LangChain4J `@Tool` 函数调用**(LLM 自主编排)

---

## 一、知识图谱(Neo4j)

### 1.1 图谱 Schema

```
节点:
  (:Article {lawName, articleNumber, chapter, text})   法条,主键 lawName+articleNumber
  (:CaseType {name})                                    案由,如"工伤赔偿""离婚纠纷"
  (:Concept  {name})                                    法律概念,如"违约责任""试用期"

关系:
  (:Article)-[:REFERS_TO]->(:Article)    法条引用(正则抽取,确定性)
  (:CaseType)-[:APPLIES]->(:Article)     案由适用法条(LLM 抽取)
  (:Concept)-[:RELATES]->(:Article)      概念关联法条(LLM 抽取)
```

### 1.2 关系来源(两阶段,控制成本)

**阶段 A — 引用边(正则,免费,确定性):**
- 在法条文本里匹配 `本法第X条`、`依照第X条`、`第X条、第Y条的规定` 等模式。
- 同一部法律内,源法条 → 目标法条建 `REFERS_TO` 边。
- 启动时构建,无 API 调用。

**阶段 B — 案由/概念边(LLM 抽取,一次性,缓存到仓库):**
- 写一个**离线脚本/一次性接口** `/test/build-graph`,用 LLM 给每条法条打标:它属于哪些案由、关联哪些概念。
- 结果存成 `src/main/resources/graph/legal-graph-relations.json`(提交到仓库)。
- 启动时直接读 JSON 灌入 Neo4j,**不每次启动都调 API**(沿用现有 `EMBEDDING_VERSION` 版本号思路,新增 `GRAPH_VERSION`)。
- 案由词典先用 `HybridContentRetriever` 里已有的 `LEGAL_TERMS` 做种子,LLM 扩充。

### 1.3 构建流程

启动时 `Neo4jGraphConfig`:
1. 检查 Redis 里 `legal:graph:version`,与 `GRAPH_VERSION` 一致则跳过。
2. 不一致:清空图 → 从 `allSegments` 建 `Article` 节点 → 正则建 `REFERS_TO` → 读 JSON 建 `CaseType/Concept` 节点和边 → 写版本号。
3. `allSegments` 复用 `CommonConfig.parseDocuments()`,不重复解析。

---

## 二、Agentic RAG(@Tool 函数调用)

### 2.1 工具集(新建 `LegalTools` 组件)

```java
@Component
class LegalTools {
    @Tool("根据自然语言问题语义检索相关法条,适合口语化、概念性问题")
    String vectorSearch(String query)          // 委托 hybridRetriever

    @Tool("查询某个案由或法律概念关联的法条,以及这些法条引用的其他法条(多跳)")
    String graphQuery(String caseTypeOrConcept) // Neo4j Cypher 多跳

    @Tool("按法律名称和条号精确查询某一条法条原文")
    String exactArticle(String lawName, String articleNumber) // 精确匹配
}
```

工具返回**格式化文本**(法名+条号+原文),LLM 读完决定是否再调。

### 2.2 AiService 改造

`ConsultantService` 从:
```java
@AiService(..., contentRetriever = "contentRetriever")
```
改为:
```java
@AiService(..., tools = "legalTools")   // 移除 contentRetriever,改用 tools
```
- 移除自动 RAG 注入,LLM 通过工具自主检索。
- `Flux<String>` 流式签名**不变**,LangChain4J 支持"工具调用→流式生成最终答案"。
- 把 `QueryRewriteRetriever` 的改写逻辑并入 `vectorSearch` 工具内部(可选),不再是全局强制步骤。

### 2.3 System Prompt 升级

在 `system.txt` 增加工具使用策略,引导 LLM:
- 简单概念题(如"什么是诉讼时效")→ 可不检索直接答。
- 精确法条题(如"民法典第148条")→ 用 `exactArticle`。
- 复杂多跳题(如"工伤后公司不赔能告谁、依据什么")→ 先 `graphQuery` 找案由关联法条+引用链,再 `vectorSearch` 补细节。

---

## 三、依赖与基础设施

### 3.1 `pom.xml`
新增 Neo4j Java Driver(轻量,不引 spring-data-neo4j 全家桶,避免和 MyBatis-Plus 冲突):
```xml
<dependency>
  <groupId>org.neo4j.driver</groupId>
  <artifactId>neo4j-java-driver</artifactId>
</dependency>
```

### 3.2 `docker-compose.yml`
新增 neo4j 服务:
```yaml
neo4j:
  image: neo4j:5-community
  container_name: consultant-neo4j
  environment:
    NEO4J_AUTH: neo4j/your_password
  ports: ["7474:7474", "7687:7687"]
  volumes: [neo4j-data:/data]
```

### 3.3 `application.yml` + `.example`
新增 Neo4j 连接配置(用环境变量):
```yaml
neo4j:
  uri: ${NEO4J_URI:bolt://localhost:7687}
  username: ${NEO4J_USERNAME:neo4j}
  password: ${NEO4J_PASSWORD}
```

---

## 四、文件清单

**新增:**
| 文件 | 作用 |
|---|---|
| `aiservice/tools/LegalTools.java` | 3 个 @Tool,Agent 的能力 |
| `aiservice/config/Neo4jGraphConfig.java` | Neo4j 连接 bean + 启动建图 |
| `aiservice/graph/LegalGraphBuilder.java` | 正则抽引用 + 灌图逻辑 |
| `aiservice/graph/ArticleReferenceExtractor.java` | 正则抽"本法第X条" |
| `controller/GraphController.java` | `/test/build-graph` 一次性LLM抽案由/概念;`/graph/query` 调试 |
| `src/main/resources/graph/legal-graph-relations.json` | LLM 抽取结果缓存(提交仓库) |

**修改:**
| 文件 | 改动 |
|---|---|
| `aiservice/ConsultantService.java` | `contentRetriever` → `tools` |
| `aiservice/config/CommonConfig.java` | 暴露 hybridRetriever 给 LegalTools;保留所有现有 retriever bean |
| `src/main/resources/system.txt` | 增加工具使用策略 |
| `pom.xml` / `docker-compose.yml` / `application.yml(.example)` | 加 Neo4j |
| `README.md` | 更新架构图、技术栈、启动步骤 |

**完全不动(保护消融实验):**
- `HybridContentRetriever` / `KeywordContentRetriever` / `QueryRewriteRetriever` / `LegalDocumentSplitter` / `TestController` 全部保留,`/test/batch` 消融实验继续可跑。

---

## 五、实施顺序

1. **基础设施**:docker-compose 加 neo4j、pom 加 driver、yml 加配置 → `docker compose up` 起容器验证连通。
2. **图谱构建(阶段A)**:`ArticleReferenceExtractor` + `LegalGraphBuilder` 正则建 Article+REFERS_TO → Neo4j Browser 验证节点/边数量。
3. **图谱构建(阶段B)**:`/test/build-graph` 跑一次 LLM 抽案由/概念 → 生成 JSON → 灌图。
4. **工具层**:`LegalTools` 3 个工具,各自单测(graphQuery 出多跳结果、exactArticle 命中、vectorSearch 委托正常)。
5. **接入 Agent**:改 `ConsultantService` + `system.txt` → 验证流式 + 工具调用链路。
6. **回归**:跑 `/test/batch` 确认消融实验仍正常;手测几个复杂多跳问题对比改造前后。
7. **文档**:更新 README,加一段"Agentic RAG + 知识图谱"架构说明(简历/面试用)。

---

## 六、风险与验证点

- **流式 + 工具兼容性**:LangChain4J 1.0.1-beta6 的 streaming AiService + tools 需实测。若 `Flux` 流式下工具调用有问题,退路是非流式 `String chat()` 或用 `TokenStream`。**第 5 步必须先验证这条链路再继续。**
- **LLM 抽案由/概念质量**:阶段B 结果需人工抽查 JSON,避免错误关系污染图谱。先小批量(如民法典)验证 prompt 效果再全量。
- **Neo4j 密码**:不进 Git,走环境变量(沿用现有 `.env` 模式)。
- **响应延迟**:Agent 多轮工具调用会比固定管道慢(多次 LLM 往返)。简历话术上这是"用延迟换准确率和推理能力"的权衡,可在 README 量化对比。

---

## 七、简历价值

改造后可写:
> 将固定 RAG 管道重构为 **Agentic RAG**,基于 LangChain4J 函数调用让 LLM 自主编排检索策略;引入 **Neo4j 知识图谱**建模法条引用关系与案由-法条映射,支持多跳法律推理(如"工伤赔偿→工伤认定→劳动关系确认"的法条链路追溯),解决纯向量检索无法处理的关系型法律问题。
