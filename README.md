# 法律智能问答系统

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)
![LangChain4J](https://img.shields.io/badge/LangChain4J-1.0-blue)
![Neo4j](https://img.shields.io/badge/Neo4j-5-008CC1)
![License](https://img.shields.io/badge/license-MIT-green)

基于 **Agentic RAG + 知识图谱** 的法律智能问答系统。LLM 通过函数调用自主编排检索策略（向量检索 / 图谱多跳查询 / 精确法条匹配），结合 Neo4j 知识图谱建模法条引用关系与案由-法条映射，支持多跳法律推理。

## 功能特性

- **Agentic RAG**：LLM 通过工具调用自主决定检索策略（语义检索、图谱查询、精确匹配），不再走固定管道
- **知识图谱**：Neo4j 建模法条引用关系（REFERS_TO）、案由→法条（APPLIES）、概念→法条（RELATES），支持多跳推理
- **混合检索**：向量语义检索 + 关键词检索，加权 RRF 融合，召回率 87.2%，精确率 91.1%
- **查询改写**：自动改写用户口语化问题为法律专业查询
- **法条溯源**：展示回答所引用的法律条文出处，支持悬浮预览
- **流式响应**：打字机效果逐字输出，体验流畅
- **用户系统**：支持注册/登录，JWT 鉴权
- **对话持久化**：登录用户对话存储到 MySQL，跨设备保留历史
- **游客模式**：无需登录即可使用，对话存储在浏览器本地
- **多轮对话**：支持上下文记忆，基于 Redis 的对话窗口管理
- **暗色模式**：支持明/暗主题切换
- **响应式布局**：适配桌面端和移动端

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5 |
| AI 框架 | LangChain4J 1.0 |
| 大语言模型 | 通义千问（Qwen-Plus） |
| 向量模型 | text-embedding-v3 |
| 向量数据库 | Redis + RediSearch |
| 知识图谱 | Neo4j 5 (Cypher) |
| 对话记忆 | Redis |
| 业务数据库 | MySQL 8.0 |
| ORM | MyBatis-Plus |
| 鉴权 | 手写 JWT（com.auth0 java-jwt） |
| 密码加密 | BCrypt |
| 前端 | Vue 3 + Tailwind CSS（单页面） |
| 文档解析 | Apache Tika |

## 系统架构

```
用户 ──→ index.html (Vue 3)
           │
           ├── /chat ──→ ChatController ──→ ConsultantService (Agentic AiService)
           │                                      │
           │                                      ├── 🧠 LLM 自主编排工具调用
           │                                      │     ├── vectorSearch    → 混合检索(向量+关键词RRF)
           │                                      │     ├── graphQuery      → Neo4j 知识图谱多跳查询
           │                                      │     └── exactArticle    → 法名+条号精确匹配
           │                                      │
           │                                      └── 对话记忆 (Redis ChatMemoryStore)
           │
           ├── /api/user/* ──→ UserController ──→ MySQL (用户表)
           └── /api/conversation/* ──→ ConversationController ──→ MySQL (对话表/消息表)

知识图谱 Schema (Neo4j):
  (:Article {lawName, articleNumber, chapter, text})
     ├── [:REFERS_TO] → (:Article)          法条引用关系（正则自动抽取）
  (:CaseType {name})
     └── [:APPLIES] → (:Article)            案由适用法条（LLM 抽取）
  (:Concept {name})
     └── [:RELATES] → (:Article)            概念关联法条（LLM 抽取）
```

## 项目结构

```
src/main/java/org/example/consultant3/
├── Consultant3Application.java          # 启动类
├── controller/
│   ├── ChatController.java              # 对话接口（流式响应 + 可选鉴权）
│   ├── UserController.java              # 注册/登录接口
│   ├── ConversationController.java      # 对话管理接口
│   ├── TestController.java              # 消融实验测试接口
│   └── GraphController.java             # 知识图谱管理接口
├── aiservice/
│   ├── ConsultantService.java           # Agentic AI 服务（tools 模式）
│   ├── config/
│   │   ├── CommonConfig.java            # 核心配置（向量库 + 检索器 + 图谱触发）
│   │   ├── HybridContentRetriever.java  # 混合检索器（向量 + 关键词 RRF 融合）
│   │   ├── KeywordContentRetriever.java # 关键词检索器
│   │   ├── QueryRewriteRetriever.java   # 查询改写检索器
│   │   └── LegalDocumentSplitter.java   # 法律文档按条切分器
│   ├── graph/
│   │   ├── Neo4jGraphConfig.java        # Neo4j 连接 + 图谱构建
│   │   └── ArticleReferenceExtractor.java # 正则抽取法条引用关系
│   └── tools/
│       └── LegalTools.java              # Agent 工具集（3 个 @Tool）
├── entity/
│   ├── User.java                        # 用户实体
│   ├── Conversation.java                # 对话实体
│   └── Message.java                     # 消息实体
├── mapper/
│   ├── UserMapper.java                  # 用户 Mapper
│   ├── ConversationMapper.java          # 对话 Mapper
│   └── MessageMapper.java              # 消息 Mapper
├── service/
│   └── UserService.java                 # 用户业务逻辑
├── util/
│   └── JwtUtil.java                     # JWT 工具类
├── config/
│   ├── JwtInterceptor.java              # JWT 鉴权拦截器
│   └── WebConfig.java                   # Web 配置
└── repository/
    └── RedisChatMemoryStore.java         # Redis 对话记忆存储

src/main/resources/
├── application.yml                       # 应用配置（环境变量）
├── application.yml.example               # 配置示例
├── system.txt                            # AI 系统提示词 + 工具策略
├── static/index.html                     # 前端页面
├── content/                              # 法律文档目录
├── graph/                                # 图谱关系缓存
│   └── legal-graph-relations.json        # LLM 抽取的案由/概念关系
└── db/schema.sql                         # 数据库建表 SQL
```

## 环境要求

- JDK 17+
- Maven 3.6+
- Docker Desktop（运行 MySQL、Redis、Neo4j）

## 快速启动

### 1. 启动所有服务

```bash
docker compose up -d
```

这会启动 MySQL、Redis 和 Neo4j 三个容器。

### 2. 初始化数据库（仅首次）

```bash
docker exec -i consultant-mysql mysql -uroot -proot123 < src/main/resources/db/schema.sql
```

### 3. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY 和 NEO4J_PASSWORD
```

### 4. 运行项目

在 IDEA 中运行 `Consultant3Application.java`，或：

```bash
mvn spring-boot:run
```

启动时自动完成：
- 法律文档向量化（版本号跳过已有）
- 知识图谱构建：Article 节点 + REFERS_TO 引用边（正则自动抽取）

### 5. （可选）构建图谱语义关系

LLM 抽取案由和概念关系（消耗 API 额度）：

```bash
# 小规模测试
curl "http://localhost:8080/test/build-graph?limit=20"

# 全量构建
curl "http://localhost:8080/test/build-graph"

# 灌入 Neo4j
curl "http://localhost:8080/test/load-graph-relations"
```

### 6. 访问

- 前端页面：http://localhost:8080
- Neo4j Browser：http://localhost:7474（neo4j / neo4j123）

## 数据库设计

共 3 张表，不使用外键约束：

- **user** — 用户表（id, username, password, nickname, created_at）
- **conversation** — 对话表（id, user_id, title, created_at, updated_at）
- **message** — 消息表（id, conversation_id, role, content, created_at）

## API 接口

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/user/register` | 用户注册 | 无需 |
| POST | `/api/user/login` | 用户登录 | 无需 |
| GET | `/chat` | 流式对话 | 可选 |
| GET | `/chat/sources` | 法条溯源 | 可选 |
| GET | `/api/conversation/list` | 对话列表 | 需要 |
| POST | `/api/conversation/create` | 新建对话 | 需要 |
| DELETE | `/api/conversation/{id}` | 删除对话 | 需要 |
| GET | `/api/conversation/{id}/messages` | 历史消息 | 需要 |
| GET | `/test/build-graph` | LLM 抽取图谱语义关系 | 手动触发 |
| GET | `/test/load-graph-relations` | 语义关系灌入 Neo4j | 手动触发 |
| GET | `/test/graph-query` | 调试：直接执行 Cypher | 开发用 |
