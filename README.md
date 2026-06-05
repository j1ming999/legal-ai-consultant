# 法律智能问答系统

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)
![LangChain4J](https://img.shields.io/badge/LangChain4J-1.0-blue)
![License](https://img.shields.io/badge/license-MIT-green)

基于 RAG（检索增强生成）技术的法律智能问答系统，支持法律法规的智能检索与问答，提供法条溯源功能。

## 功能特性

- **智能法律问答**：基于大语言模型，结合法律文档知识库进行准确回答
- **混合检索**：向量语义检索 + 关键词检索，加权 RRF 融合，提升检索准确率
- **查询改写**：自动改写用户问题，优化检索效果
- **法条溯源**：展示回答所引用的法律条文出处，支持悬浮预览
- **流式响应**：打字机效果逐字输出，体验流畅
- **用户系统**：支持注册/登录，JWT 鉴权
- **对话持久化**：登录用户对话存储到 MySQL，跨设备保留历史
- **游客模式**：无需登录即可使用，对话存储在浏览器本地
- **多轮对话**：支持上下文记忆，基于 Redis 的对话窗口管理
- **暗色模式**：支持明/暗主题切换
- **响应式布局**：适配桌面端和移动端

## 技术栈

| 层级 | 技术                        |
|------|---------------------------|
| 后端框架 | Spring Boot 3.5           |
| AI 框架 | LangChain4J 1.0           |
| 大语言模型 | 通义千问（Qwen-Plus）           |
| 向量模型 | text-embedding-v3         |
| 向量数据库 | Redis + RediSearch        |
| 对话记忆 | Redis                     |
| 业务数据库 | MySQL 8.0                 |
| ORM | MyBatis-Plus              |
| 鉴权 | 手写 JWT（com.auth0 java-jwt） |
| 密码加密 | BCrypt                    |
| 前端 | Vue 3 + Tailwind CSS（单页面） |
| 文档解析 | Apache Tika               |

## 系统架构

```
用户 ──→ index.html (Vue 3)
           │
           ├── /chat ──→ ChatController ──→ ConsultantService (LangChain4J AiService)
           │                                      │
           │                                      ├── 查询改写 (QueryRewriteRetriever)
           │                                      ├── 混合检索 (HybridContentRetriever)
           │                                      │     ├── 向量检索 (Redis + RediSearch)
           │                                      │     └── 关键词检索 (KeywordContentRetriever)
           │                                      └── 对话记忆 (Redis ChatMemoryStore)
           │
           ├── /api/user/* ──→ UserController ──→ MySQL (用户表)
           └── /api/conversation/* ──→ ConversationController ──→ MySQL (对话表/消息表)
```

## 项目结构

```
src/main/java/org/example/consultant3/
├── Consultant3Application.java          # 启动类
├── controller/
│   ├── ChatController.java              # 对话接口（流式响应 + 可选鉴权）
│   ├── UserController.java              # 注册/登录接口
│   ├── ConversationController.java      # 对话管理接口
│   └── TestController.java              # 测试接口
├── aiservice/
│   ├── ConsultantService.java           # LangChain4J AI 服务定义
│   └── config/
│       ├── CommonConfig.java            # RAG 配置（向量库、检索器、文档加载）
│       ├── HybridContentRetriever.java  # 混合检索器（向量 + 关键词 RRF 融合）
│       ├── KeywordContentRetriever.java # 关键词检索器
│       ├── QueryRewriteRetriever.java   # 查询改写检索器
│       └── LegalDocumentSplitter.java   # 法律文档按条切分器
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
├── application.yml                       # 应用配置
├── system.txt                            # AI 系统提示词
├── static/index.html                     # 前端页面
├── content/                              # 法律文档目录
└── db/schema.sql                         # 数据库建表 SQL
```

## 环境要求

- JDK 17+
- Maven 3.6+
- Docker Desktop（用于运行 MySQL 和 Redis）

## 快速启动

### 1. 启动数据库

```bash
docker start consultant-mysql redis-vector
```

### 2. 初始化数据库（仅首次）

```bash
docker exec -i consultant-mysql mysql -uroot -proot123 < src/main/resources/db/schema.sql
```

### 3. 运行项目

在 IDEA 中运行 `Consultant3Application.java`，或：

```bash
mvn spring-boot:run
```

### 4. 访问

打开浏览器访问 http://localhost:8080

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
