# 用户系统 + MySQL 持久化 设计文档

## 概述

为法律智能问答系统增加用户注册/登录和对话持久化功能。支持游客模式（不登录也能用）和登录模式（对话按账号存储到 MySQL）。

## 数据库设计

MySQL 数据库 `consultant`，3 张表，不使用外键约束。

### user 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT PK | 主键 |
| username | VARCHAR(50) NOT NULL UNIQUE | 用户名 |
| password | VARCHAR(200) NOT NULL | BCrypt 加密密码 |
| nickname | VARCHAR(50) | 昵称 |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 注册时间 |

### conversation 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT PK | 主键 |
| user_id | BIGINT NOT NULL | 所属用户 |
| title | VARCHAR(100) DEFAULT '新对话' | 对话标题 |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_at | DATETIME DEFAULT ... ON UPDATE ... | 最近活跃时间 |

索引：`idx_user_id (user_id)`

### message 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT PK | 主键 |
| conversation_id | BIGINT NOT NULL | 所属对话 |
| role | VARCHAR(20) NOT NULL | 'user' 或 'assistant' |
| content | TEXT NOT NULL | 消息内容 |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 发送时间 |

索引：`idx_conversation_id (conversation_id)`

## 技术方案

### 方案选择：手写 JWT + MyBatis-Plus

不引入 Spring Security 框架，手写 JWT 拦截器 + MyBatis-Plus ORM。理由：代码量少、易理解、毕设答辩好讲、对现有 AI 代码无侵入。

### Maven 新增依赖

- `mysql-connector-j` — MySQL 驱动
- `mybatis-plus-spring-boot3-starter` — ORM
- `java-jwt` (com.auth0) — JWT 生成/解析
- `spring-security-crypto` — 仅用 BCryptPasswordEncoder 加密密码

### 包结构（新增部分）

```
org.example.consultant3
├── controller
│   ├── ChatController.java        ← 改造：加可选鉴权、对话持久化
│   ├── UserController.java        ← 新增：注册、登录
│   └── ConversationController.java ← 新增：对话列表、删除、历史消息
├── entity
│   ├── User.java                  ← 新增
│   ├── Conversation.java          ← 新增
│   └── Message.java               ← 新增
├── mapper
│   ├── UserMapper.java            ← 新增
│   ├── ConversationMapper.java    ← 新增
│   └── MessageMapper.java         ← 新增
├── service
│   └── UserService.java           ← 新增
├── util
│   └── JwtUtil.java               ← 新增
├── config
│   └── JwtInterceptor.java        ← 新增
└── aiservice/...                  ← 不动
```

## API 设计

### 公开接口（无需登录）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/user/register` | 注册 |
| POST | `/api/user/login` | 登录，返回 JWT token |

### 可选鉴权接口（游客也能用）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/chat` | 流式对话。已登录时消息存 MySQL，未登录时不存 |
| GET | `/chat/sources` | 法条溯源 |

### 需登录接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/conversation/list` | 当前用户的对话列表 |
| POST | `/api/conversation/create` | 新建对话 |
| DELETE | `/api/conversation/{id}` | 删除对话及其消息 |
| GET | `/api/conversation/{id}/messages` | 获取对话历史消息 |

## 鉴权流程

1. 用户登录 → 后端返回 JWT token（有效期 7 天）
2. 前端把 token 存 localStorage
3. 每次请求在 Header 带 `Authorization: Bearer <token>`
4. `JwtInterceptor` 拦截请求：
   - 公开接口：直接放行
   - 可选鉴权接口：有 token 就解析出 userId 放入请求属性，没有也放行
   - 需登录接口：没 token 或 token 无效返回 401
5. Controller 中通过 `request.getAttribute("userId")` 获取当前用户

## 游客模式 vs 登录模式

| 功能 | 游客 | 已登录 |
|------|------|--------|
| 对话 | 正常使用 | 正常使用 |
| 对话历史存储 | 前端 localStorage | MySQL（按账号隔离） |
| 侧边栏对话列表 | localStorage 本地管理 | 后端 API 加载 |
| 换设备保留对话 | 不能 | 能 |

## 前端改造

在现有 index.html 上改造：

1. **登录状态**：Vue 中加 `isLoggedIn`、`currentUser`、`token` 状态
2. **未登录时**：右上角显示"登录"按钮，点击弹出登录/注册表单（模态框或替换主区域）
3. **已登录时**：右上角显示用户名 + "退出"按钮
4. **对话管理**：
   - 已登录：侧边栏从后端 API 加载对话列表，消息从后端加载
   - 未登录：保持现有 localStorage 逻辑
5. **发消息**：已登录时请求带 Authorization header
6. **保留不变**：流式响应、打字效果、Markdown 渲染、法条溯源、暗色模式、欢迎页推荐问题

## 现有代码改造范围

### 需修改

- `ChatController.java`：加可选鉴权，已登录时存消息到 MySQL
- `application.yml`：加 MySQL 数据源配置
- `pom.xml`：加新依赖
- `index.html`：加登录/注册 UI，对话管理改为后端驱动
- `WebMvcConfigurer`（新增）：注册 JwtInterceptor

### 不修改

- `ConsultantService.java`
- `CommonConfig.java`（RAG 配置）
- `LegalDocumentSplitter.java`
- `HybridContentRetriever.java`
- `KeywordContentRetriever.java`
- `QueryRewriteRetriever.java`
- `RedisChatMemoryStore.java`（保留，用于 LangChain4J 对话上下文窗口）
- `TestController.java`

## 配置新增

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/consultant?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4
    username: root
    password: root123
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

jwt:
  secret: consultant-legal-ai-jwt-secret-key-2026
  expiration: 604800000  # 7天
```
