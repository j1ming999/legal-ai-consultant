# 用户系统 + MySQL 持久化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为法律智能问答系统增加用户注册/登录和对话持久化功能，支持游客模式和登录模式。

**Architecture:** 手写 JWT 拦截器实现鉴权，MyBatis-Plus 做 ORM，MySQL 存储用户和对话数据。游客模式保持现有 localStorage 逻辑，登录模式对话存 MySQL。对现有 AI/RAG 代码零侵入。

**Tech Stack:** Spring Boot 3.5, MyBatis-Plus, com.auth0 java-jwt, BCrypt, MySQL 8.0

**Spec:** `docs/superpowers/specs/2026-04-18-user-system-design.md`

---

## File Structure

### 新增文件

| 文件 | 职责 |
|------|------|
| `src/main/resources/db/schema.sql` | 建表 SQL |
| `src/main/java/.../entity/User.java` | 用户实体 |
| `src/main/java/.../entity/Conversation.java` | 对话实体 |
| `src/main/java/.../entity/Message.java` | 消息实体 |
| `src/main/java/.../mapper/UserMapper.java` | 用户 Mapper |
| `src/main/java/.../mapper/ConversationMapper.java` | 对话 Mapper |
| `src/main/java/.../mapper/MessageMapper.java` | 消息 Mapper |
| `src/main/java/.../util/JwtUtil.java` | JWT 生成/解析工具 |
| `src/main/java/.../config/WebConfig.java` | WebMvcConfigurer 注册拦截器 |
| `src/main/java/.../config/JwtInterceptor.java` | JWT 鉴权拦截器 |
| `src/main/java/.../service/UserService.java` | 用户注册/登录业务逻辑 |
| `src/main/java/.../controller/UserController.java` | 注册/登录 API |
| `src/main/java/.../controller/ConversationController.java` | 对话管理 API |

> 以下 `...` 均指 `org/example/consultant3`

### 修改文件

| 文件 | 改动 |
|------|------|
| `pom.xml` | 添加 4 个依赖 |
| `src/main/resources/application.yml` | 添加 MySQL 数据源 + MyBatis-Plus + JWT 配置 |
| `src/main/java/.../Consultant3Application.java` | 添加 `@MapperScan` 注解 |
| `src/main/java/.../controller/ChatController.java` | 可选鉴权 + 消息持久化 |
| `src/main/resources/static/index.html` | 登录/注册 UI + 对话后端化 |

---

## Task 1: 建表 SQL + Maven 依赖 + 配置

**Files:**
- Create: `src/main/resources/db/schema.sql`
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: 创建建表 SQL 文件**

```sql
-- src/main/resources/db/schema.sql

CREATE DATABASE IF NOT EXISTS consultant
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE consultant;

CREATE TABLE IF NOT EXISTS user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(200) NOT NULL,
    nickname    VARCHAR(50),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    title       VARCHAR(100) DEFAULT '新对话',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT NOT NULL,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 在 MySQL 中执行建表 SQL**

```bash
docker exec -i consultant-mysql mysql -uroot -proot123 < src/main/resources/db/schema.sql
```

Expected: 无报错，3 张表创建成功。

- [ ] **Step 3: 验证表已创建**

```bash
docker exec consultant-mysql mysql -uroot -proot123 -e "USE consultant; SHOW TABLES;"
```

Expected: 输出 `user`, `conversation`, `message` 三张表。

- [ ] **Step 4: 在 pom.xml 的 `</dependencies>` 前添加 4 个依赖**

```xml
<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- MyBatis-Plus -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>

<!-- JWT -->
<dependency>
    <groupId>com.auth0</groupId>
    <artifactId>java-jwt</artifactId>
    <version>4.4.0</version>
</dependency>

<!-- BCrypt 密码加密（仅用 BCryptPasswordEncoder，不引入 Spring Security 框架） -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

- [ ] **Step 5: 在 application.yml 中添加 MySQL + MyBatis-Plus + JWT 配置**

在现有 `spring:` 下添加 `datasource`，并添加 `mybatis-plus` 和 `jwt` 顶层配置：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/consultant?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4
    username: root
    password: root123
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

jwt:
  secret: consultant-legal-ai-jwt-secret-key-2026
  expiration: 604800000
```

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/resources/db/schema.sql
git commit -m "feat: add MySQL schema, Maven dependencies, and datasource config"
```

---

## Task 2: 实体类 + Mapper 接口

**Files:**
- Create: `src/main/java/.../entity/User.java`
- Create: `src/main/java/.../entity/Conversation.java`
- Create: `src/main/java/.../entity/Message.java`
- Create: `src/main/java/.../mapper/UserMapper.java`
- Create: `src/main/java/.../mapper/ConversationMapper.java`
- Create: `src/main/java/.../mapper/MessageMapper.java`
- Modify: `src/main/java/.../Consultant3Application.java`

- [ ] **Step 1: 创建 User 实体**

```java
// src/main/java/org/example/consultant3/entity/User.java
package org.example.consultant3.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 2: 创建 Conversation 实体**

```java
// src/main/java/org/example/consultant3/entity/Conversation.java
package org.example.consultant3.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **Step 3: 创建 Message 实体**

```java
// src/main/java/org/example/consultant3/entity/Message.java
package org.example.consultant3.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: 创建 3 个 Mapper 接口**

```java
// src/main/java/org/example/consultant3/mapper/UserMapper.java
package org.example.consultant3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.consultant3.entity.User;

public interface UserMapper extends BaseMapper<User> {
}
```

```java
// src/main/java/org/example/consultant3/mapper/ConversationMapper.java
package org.example.consultant3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.consultant3.entity.Conversation;

public interface ConversationMapper extends BaseMapper<Conversation> {
}
```

```java
// src/main/java/org/example/consultant3/mapper/MessageMapper.java
package org.example.consultant3.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.consultant3.entity.Message;

public interface MessageMapper extends BaseMapper<Message> {
}
```

- [ ] **Step 5: 在 Consultant3Application.java 添加 @MapperScan**

在类上添加注解：

```java
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("org.example.consultant3.mapper")
public class Consultant3Application {
    public static void main(String[] args) {
        SpringApplication.run(Consultant3Application.class, args);
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/consultant3/entity/ src/main/java/org/example/consultant3/mapper/ src/main/java/org/example/consultant3/Consultant3Application.java
git commit -m "feat: add User/Conversation/Message entities and MyBatis-Plus mappers"
```

---

## Task 3: JWT 工具类 + 拦截器 + WebConfig

**Files:**
- Create: `src/main/java/.../util/JwtUtil.java`
- Create: `src/main/java/.../config/JwtInterceptor.java`
- Create: `src/main/java/.../config/WebConfig.java`

- [ ] **Step 1: 创建 JwtUtil**

```java
// src/main/java/org/example/consultant3/util/JwtUtil.java
package org.example.consultant3.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    public String generateToken(Long userId, String username) {
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withExpiresAt(new Date(System.currentTimeMillis() + expiration))
                .sign(Algorithm.HMAC256(secret));
    }

    public DecodedJWT verifyToken(String token) {
        return JWT.require(Algorithm.HMAC256(secret))
                .build()
                .verify(token);
    }
}
```

- [ ] **Step 2: 创建 JwtInterceptor**

```java
// src/main/java/org/example/consultant3/config/JwtInterceptor.java
package org.example.consultant3.config;

import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.consultant3.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    private static final String[] PUBLIC_PATHS = {
            "/api/user/register",
            "/api/user/login"
    };

    private static final String[] OPTIONAL_AUTH_PATHS = {
            "/chat",
            "/chat/sources",
            "/test/"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getRequestURI();

        // 公开接口直接放行
        for (String pub : PUBLIC_PATHS) {
            if (path.equals(pub)) return true;
        }

        // 静态资源放行
        if (path.equals("/") || path.endsWith(".html") || path.endsWith(".css")
                || path.endsWith(".js") || path.endsWith(".ico") || path.endsWith(".png")) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        // 可选鉴权接口：有 token 就解析，没有也放行
        boolean isOptional = false;
        for (String opt : OPTIONAL_AUTH_PATHS) {
            if (path.startsWith(opt)) {
                isOptional = true;
                break;
            }
        }

        if (token != null) {
            try {
                DecodedJWT jwt = jwtUtil.verifyToken(token);
                request.setAttribute("userId", jwt.getClaim("userId").asLong());
                request.setAttribute("username", jwt.getClaim("username").asString());
                return true;
            } catch (Exception e) {
                if (isOptional) return true;
                response.setStatus(401);
                response.setContentType("application/json;charset=utf-8");
                response.getWriter().write("{\"error\":\"token无效或已过期\"}");
                return false;
            }
        }

        if (isOptional) return true;

        // 需登录的接口，没 token 返回 401
        if (path.startsWith("/api/")) {
            response.setStatus(401);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().write("{\"error\":\"请先登录\"}");
            return false;
        }

        return true;
    }
}
```

- [ ] **Step 3: 创建 WebConfig 注册拦截器**

```java
// src/main/java/org/example/consultant3/config/WebConfig.java
package org.example.consultant3.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**");
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/consultant3/util/ src/main/java/org/example/consultant3/config/JwtInterceptor.java src/main/java/org/example/consultant3/config/WebConfig.java
git commit -m "feat: add JWT auth with interceptor supporting public/optional/required modes"
```

---

## Task 4: UserService + UserController（注册/登录）

**Files:**
- Create: `src/main/java/.../service/UserService.java`
- Create: `src/main/java/.../controller/UserController.java`

- [ ] **Step 1: 创建 UserService**

```java
// src/main/java/org/example/consultant3/service/UserService.java
package org.example.consultant3.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.consultant3.entity.User;
import org.example.consultant3.mapper.UserMapper;
import org.example.consultant3.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Map<String, Object> register(String username, String password, String nickname) {
        if (username == null || username.trim().length() < 2 || username.trim().length() > 50) {
            return error("用户名长度需在2-50个字符之间");
        }
        if (password == null || password.length() < 6) {
            return error("密码长度不能少于6位");
        }

        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username.trim());
        if (userMapper.selectOne(wrapper) != null) {
            return error("用户名已存在");
        }

        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname != null && !nickname.trim().isEmpty() ? nickname.trim() : username.trim());
        userMapper.insert(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        return result;
    }

    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) {
            return error("用户名和密码不能为空");
        }

        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username.trim());
        User user = userMapper.selectOne(wrapper);

        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return error("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("token", token);
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        return result;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", message);
        return result;
    }
}
```

- [ ] **Step 2: 创建 UserController**

```java
// src/main/java/org/example/consultant3/controller/UserController.java
package org.example.consultant3.controller;

import org.example.consultant3.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        return userService.register(
                body.get("username"),
                body.get("password"),
                body.get("nickname")
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return userService.login(
                body.get("username"),
                body.get("password")
        );
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/consultant3/service/ src/main/java/org/example/consultant3/controller/UserController.java
git commit -m "feat: add user registration and login with JWT token"
```

---

## Task 5: ConversationController（对话管理 API）

**Files:**
- Create: `src/main/java/.../controller/ConversationController.java`

- [ ] **Step 1: 创建 ConversationController**

```java
// src/main/java/org/example/consultant3/controller/ConversationController.java
package org.example.consultant3.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.consultant3.entity.Conversation;
import org.example.consultant3.entity.Message;
import org.example.consultant3.mapper.ConversationMapper;
import org.example.consultant3.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @GetMapping("/list")
    public Map<String, Object> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        QueryWrapper<Conversation> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("updated_at");
        List<Conversation> conversations = conversationMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("conversations", conversations);
        return result;
    }

    @PostMapping("/create")
    public Map<String, Object> create(HttpServletRequest request, @RequestBody(required = false) Map<String, String> body) {
        Long userId = (Long) request.getAttribute("userId");

        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(body != null && body.get("title") != null ? body.get("title") : "新对话");
        conversationMapper.insert(conv);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("conversation", conv);
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");

        // 验证对话属于当前用户
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !conv.getUserId().equals(userId)) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "对话不存在");
            return result;
        }

        // 删除该对话下的所有消息
        QueryWrapper<Message> msgWrapper = new QueryWrapper<>();
        msgWrapper.eq("conversation_id", id);
        messageMapper.delete(msgWrapper);

        // 删除对话
        conversationMapper.deleteById(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/{id}/messages")
    public Map<String, Object> messages(HttpServletRequest request, @PathVariable Long id) {
        Long userId = (Long) request.getAttribute("userId");

        // 验证对话属于当前用户
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null || !conv.getUserId().equals(userId)) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "对话不存在");
            return result;
        }

        QueryWrapper<Message> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", id).orderByAsc("created_at");
        List<Message> messages = messageMapper.selectList(wrapper);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("messages", messages);
        return result;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/consultant3/controller/ConversationController.java
git commit -m "feat: add conversation CRUD API (list, create, delete, messages)"
```

---

## Task 6: 改造 ChatController（可选鉴权 + 消息持久化）

**Files:**
- Modify: `src/main/java/.../controller/ChatController.java`

- [ ] **Step 1: 改造 ChatController**

将现有 ChatController 替换为支持可选鉴权和消息持久化的版本：

```java
// src/main/java/org/example/consultant3/controller/ChatController.java
package org.example.consultant3.controller;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.example.consultant3.aiservice.ConsultantService;
import org.example.consultant3.entity.Conversation;
import org.example.consultant3.entity.Message;
import org.example.consultant3.mapper.ConversationMapper;
import org.example.consultant3.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
public class ChatController {
    @Autowired
    private ConsultantService consultantService;

    @Autowired
    private ContentRetriever contentRetriever;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @RequestMapping(value = "/chat", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String memoryId, String message,
                             @RequestParam(required = false) Long conversationId,
                             HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        // 已登录用户：保存用户消息到 MySQL
        if (userId != null && conversationId != null) {
            // 验证对话属于当前用户
            Conversation conv = conversationMapper.selectById(conversationId);
            if (conv != null && conv.getUserId().equals(userId)) {
                Message userMsg = new Message();
                userMsg.setConversationId(conversationId);
                userMsg.setRole("user");
                userMsg.setContent(message);
                messageMapper.insert(userMsg);

                // 用第一条消息更新对话标题
                if ("新对话".equals(conv.getTitle())) {
                    String title = message.length() > 20 ? message.substring(0, 20) + "..." : message;
                    UpdateWrapper<Conversation> update = new UpdateWrapper<>();
                    update.eq("id", conversationId).set("title", title);
                    conversationMapper.update(null, update);
                }
            }
        }

        // memoryId 用 conversationId（登录）或前端传的值（游客）
        String effectiveMemoryId = (userId != null && conversationId != null)
                ? "user-" + userId + "-conv-" + conversationId
                : (memoryId != null ? memoryId : UUID.randomUUID().toString());

        // 收集流式响应内容，结束后存入 MySQL
        StringBuilder responseBuffer = new StringBuilder();
        final Long finalConvId = conversationId;
        final Long finalUserId = userId;

        return consultantService.chat(effectiveMemoryId, message)
                .doOnNext(responseBuffer::append)
                .doOnComplete(() -> {
                    if (finalUserId != null && finalConvId != null && responseBuffer.length() > 0) {
                        Message assistantMsg = new Message();
                        assistantMsg.setConversationId(finalConvId);
                        assistantMsg.setRole("assistant");
                        assistantMsg.setContent(responseBuffer.toString());
                        messageMapper.insert(assistantMsg);
                    }
                });
    }

    @GetMapping(value = "/chat/sources", produces = "application/json;charset=utf-8")
    public List<Map<String, String>> getSources(@RequestParam String message) {
        List<Content> results = contentRetriever.retrieve(Query.from(message));
        List<Map<String, String>> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Content c : results) {
            String lawName = c.textSegment().metadata().getString("law_name");
            String chapter = c.textSegment().metadata().getString("chapter");
            String article = c.textSegment().metadata().getString("article_number");
            String key = (lawName != null ? lawName : "") + ":" + (article != null ? article : "");
            if (seen.contains(key)) continue;
            seen.add(key);

            Map<String, String> source = new LinkedHashMap<>();
            source.put("law_name", lawName != null ? lawName : "");
            source.put("chapter", chapter != null ? chapter : "");
            source.put("article_number", article != null ? article : "");
            source.put("text_preview", c.textSegment().text().substring(0,
                    Math.min(100, c.textSegment().text().length())) + "...");
            sources.add(source);
        }
        return sources;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/example/consultant3/controller/ChatController.java
git commit -m "feat: ChatController supports optional auth and MySQL message persistence"
```

---

## Task 7: 前端改造 — 登录/注册 + 对话后端化

**Files:**
- Modify: `src/main/resources/static/index.html`

- [ ] **Step 1: 在 `<style>` 标签中追加登录弹窗样式**

在 `</style>` 结束标签之前添加：

```css
/* === Auth Modal === */
.auth-overlay {
    position: fixed; inset: 0; background: rgba(0,0,0,0.4);
    display: flex; align-items: center; justify-content: center; z-index: 200;
}
.auth-card {
    background: var(--bg); border-radius: 16px; padding: 32px;
    width: 360px; max-width: 90vw; box-shadow: 0 8px 32px rgba(0,0,0,0.15);
}
.auth-card h3 { font-size: 18px; font-weight: 600; margin-bottom: 20px; text-align: center; }
.auth-input {
    width: 100%; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px;
    background: var(--input-bg); color: var(--text); font-size: 14px; margin-bottom: 12px;
    outline: none; box-sizing: border-box;
}
.auth-input:focus { border-color: var(--text-hint); }
.auth-btn {
    width: 100%; padding: 10px; border: none; border-radius: 8px;
    background: var(--text); color: var(--bg); font-size: 14px; font-weight: 500;
    cursor: pointer; margin-bottom: 12px; transition: opacity 0.15s;
}
.auth-btn:hover { opacity: 0.85; }
.auth-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.auth-switch {
    text-align: center; font-size: 13px; color: var(--text-sub);
}
.auth-switch span { color: var(--text); cursor: pointer; font-weight: 500; }
.auth-switch span:hover { text-decoration: underline; }
.auth-error {
    background: #fee; color: #c00; padding: 8px 12px; border-radius: 6px;
    font-size: 13px; margin-bottom: 12px; text-align: center;
}
.dark-mode .auth-error { background: #3a1c1c; color: #f88; }
.user-info {
    display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-sub);
}
.user-info span { font-weight: 500; color: var(--text); }
.logout-btn {
    border: none; background: transparent; color: var(--text-hint);
    cursor: pointer; font-size: 12px; padding: 2px 6px; border-radius: 4px;
}
.logout-btn:hover { color: var(--text); background: var(--bg-alt); }
```

- [ ] **Step 2: 在 top-bar-right 区域添加登录/用户信息按钮**

将现有 `top-bar-right` div 替换为：

```html
<div class="top-bar-right">
    <button class="icon-btn" @click="createNewConversation" title="新对话">
        <i class="fas fa-plus"></i>
    </button>
    <button class="icon-btn" @click="toggleDarkMode" :title="darkMode?'浅色模式':'深色模式'">
        <i :class="darkMode?'fas fa-sun':'fas fa-moon'"></i>
    </button>
    <!-- 登录状态 -->
    <div v-if="isLoggedIn" class="user-info">
        <i class="fas fa-user-circle"></i>
        <span>{{ currentUser }}</span>
        <button class="logout-btn" @click="logout">退出</button>
    </div>
    <button v-else class="icon-btn" @click="showAuthModal = true" title="登录">
        <i class="fas fa-sign-in-alt"></i>
    </button>
</div>
```

- [ ] **Step 3: 在 `</div><!-- layout -->` 结束之前添加登录弹窗模板**

在 layout div 结束标签之前添加：

```html
<!-- Auth Modal -->
<div v-if="showAuthModal" class="auth-overlay" @click.self="showAuthModal = false">
    <div class="auth-card">
        <h3>{{ authMode === 'login' ? '登录' : '注册' }}</h3>
        <div v-if="authError" class="auth-error">{{ authError }}</div>
        <input class="auth-input" v-model="authForm.username" placeholder="用户名" @keydown.enter="handleAuth" />
        <input class="auth-input" v-model="authForm.password" type="password" placeholder="密码" @keydown.enter="handleAuth" />
        <input v-if="authMode === 'register'" class="auth-input" v-model="authForm.nickname" placeholder="昵称（可选）" @keydown.enter="handleAuth" />
        <button class="auth-btn" @click="handleAuth" :disabled="authLoading">
            {{ authLoading ? '请稍候...' : (authMode === 'login' ? '登录' : '注册') }}
        </button>
        <div class="auth-switch">
            <template v-if="authMode === 'login'">
                没有账号？<span @click="authMode = 'register'; authError = ''">立即注册</span>
            </template>
            <template v-else>
                已有账号？<span @click="authMode = 'login'; authError = ''">去登录</span>
            </template>
        </div>
    </div>
</div>
```

- [ ] **Step 4: 在 Vue setup() 中添加登录相关状态和方法**

在 `const conversations = ref([]);` 之后添加：

```javascript
// Auth state
const isLoggedIn = ref(false);
const currentUser = ref('');
const token = ref('');
const showAuthModal = ref(false);
const authMode = ref('login');
const authForm = ref({ username: '', password: '', nickname: '' });
const authError = ref('');
const authLoading = ref(false);

const getAuthHeaders = () => {
    if (token.value) {
        return { 'Authorization': 'Bearer ' + token.value };
    }
    return {};
};

const handleAuth = async () => {
    if (!authForm.value.username.trim() || !authForm.value.password) return;
    authLoading.value = true;
    authError.value = '';
    try {
        const url = authMode.value === 'login' ? '/api/user/login' : '/api/user/register';
        const resp = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(authForm.value)
        });
        const data = await resp.json();
        if (data.success) {
            token.value = data.token;
            currentUser.value = data.nickname || data.username;
            isLoggedIn.value = true;
            localStorage.setItem('auth_token', data.token);
            localStorage.setItem('auth_user', currentUser.value);
            showAuthModal.value = false;
            authForm.value = { username: '', password: '', nickname: '' };
            await loadConversationsFromServer();
        } else {
            authError.value = data.error;
        }
    } catch (e) {
        authError.value = '网络错误，请稍后重试';
    } finally {
        authLoading.value = false;
    }
};

const logout = () => {
    token.value = '';
    currentUser.value = '';
    isLoggedIn.value = false;
    localStorage.removeItem('auth_token');
    localStorage.removeItem('auth_user');
    // 切换到游客模式的本地对话
    loadConversations();
};

const loadConversationsFromServer = async () => {
    try {
        const resp = await fetch('/api/conversation/list', { headers: getAuthHeaders() });
        const data = await resp.json();
        if (data.success) {
            conversations.value = data.conversations.map(c => ({
                id: c.id.toString(),
                title: c.title,
                time: c.updatedAt ? c.updatedAt.substring(11, 16) : '',
                messages: [],
                fromServer: true
            }));
            if (conversations.value.length > 0) {
                await switchConversation(conversations.value[0].id);
            } else {
                currentConvId.value = null;
                messages.value = [];
            }
        }
    } catch (e) {
        console.error('加载对话列表失败:', e);
    }
};

const loadMessagesFromServer = async (convId) => {
    try {
        const resp = await fetch(`/api/conversation/${convId}/messages`, { headers: getAuthHeaders() });
        const data = await resp.json();
        if (data.success) {
            messages.value = data.messages.map(m => ({
                role: m.role,
                content: m.content,
                displayContent: m.content,
                visibleChars: m.content.length,
                isLoading: false,
                isStreaming: false,
                time: m.createdAt ? m.createdAt.substring(11, 16) : '',
                sources: [],
                showSources: false
            }));
        }
    } catch (e) {
        console.error('加载消息失败:', e);
    }
};
```

- [ ] **Step 5: 修改 createNewConversation 方法**

替换现有 `createNewConversation`：

```javascript
const createNewConversation = async () => {
    saveConversations();
    if (typingInterval) { clearInterval(typingInterval); typingInterval = null; }
    if (controller) { controller.abort(); controller = null; }
    isLoading.value = false;

    if (isLoggedIn.value) {
        try {
            const resp = await fetch('/api/conversation/create', {
                method: 'POST',
                headers: { ...getAuthHeaders(), 'Content-Type': 'application/json' },
                body: JSON.stringify({})
            });
            const data = await resp.json();
            if (data.success) {
                const conv = data.conversation;
                conversations.value.unshift({
                    id: conv.id.toString(), title: conv.title,
                    time: getTimeStr(), messages: [], fromServer: true
                });
                currentConvId.value = conv.id.toString();
                messages.value = [];
            }
        } catch (e) { console.error('创建对话失败:', e); }
    } else {
        const id = Date.now().toString();
        conversations.value.unshift({ id, title: '新对话', time: getTimeStr(), messages: [] });
        currentConvId.value = id;
        messages.value = [];
        saveConversations();
    }
    if (isMobile.value) sidebarOpen.value = false;
    nextTick(() => { if (textarea.value) textarea.value.focus(); });
};
```

- [ ] **Step 6: 修改 switchConversation 方法**

替换现有 `switchConversation`：

```javascript
const switchConversation = async (id) => {
    if (id === currentConvId.value) return;
    saveConversations();
    if (typingInterval) { clearInterval(typingInterval); typingInterval = null; }
    if (controller) { controller.abort(); controller = null; }
    isLoading.value = false;

    currentConvId.value = id;

    if (isLoggedIn.value) {
        await loadMessagesFromServer(id);
    } else {
        const conv = conversations.value.find(c => c.id === id);
        messages.value = (conv && conv.messages || []).map(m => ({
            ...m, displayContent: m.content, visibleChars: m.content ? m.content.length : 0,
            isLoading: false, isStreaming: false, showSources: false
        }));
    }
    saveConversations();
    if (isMobile.value) sidebarOpen.value = false;
    nextTick(scrollToBottom);
};
```

- [ ] **Step 7: 修改 deleteConversation 方法**

替换现有 `deleteConversation`：

```javascript
const deleteConversation = async (id) => {
    if (isLoggedIn.value) {
        try {
            await fetch(`/api/conversation/${id}`, {
                method: 'DELETE', headers: getAuthHeaders()
            });
        } catch (e) { console.error('删除对话失败:', e); }
    }
    conversations.value = conversations.value.filter(c => c.id !== id);
    if (currentConvId.value === id) {
        if (conversations.value.length > 0) {
            await switchConversation(conversations.value[0].id);
        } else {
            currentConvId.value = null;
            messages.value = [];
        }
    }
    if (!isLoggedIn.value) saveConversations();
};
```

- [ ] **Step 8: 修改 sendMessage 方法中的 fetch URL 和 header**

在 sendMessage 方法中，找到 `const response = await fetch(...)` 这行，替换为：

```javascript
// 构建请求 URL
let chatUrl = `/chat?message=${encodeURIComponent(userMessage.content)}&memoryId=${currentConvId.value}`;
if (isLoggedIn.value) {
    chatUrl += `&conversationId=${currentConvId.value}`;
}

const response = await fetch(chatUrl, {
    signal: controller.signal,
    headers: getAuthHeaders()
});
```

同时在 finally 块中，将 `saveConversations()` 替换为：

```javascript
if (!isLoggedIn.value) saveConversations();
```

- [ ] **Step 9: 修改 onMounted 以恢复登录状态**

在 `onMounted` 回调中，在 `loadConversations()` 之前添加：

```javascript
// 恢复登录状态
const savedToken = localStorage.getItem('auth_token');
const savedUser = localStorage.getItem('auth_user');
if (savedToken) {
    token.value = savedToken;
    currentUser.value = savedUser || '';
    isLoggedIn.value = true;
    loadConversationsFromServer();
} else {
    loadConversations();
}
```

并删除原来的 `loadConversations();` 调用（已包含在 else 分支中）。

- [ ] **Step 10: 在 setup return 中添加新的状态和方法**

在 return 对象中追加：

```javascript
isLoggedIn, currentUser, token, showAuthModal, authMode, authForm, authError, authLoading,
handleAuth, logout, getAuthHeaders,
```

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: frontend login/register modal, server-driven conversation management"
```

---

## Task 8: 验证测试

- [ ] **Step 1: 启动项目**

```bash
cd E:/ideaproject/consultant3 && mvn spring-boot:run
```

Expected: 启动无报错，日志显示 MySQL 数据源连接成功。

- [ ] **Step 2: 测试注册**

```bash
curl -X POST http://localhost:8080/api/user/register -H "Content-Type: application/json" -d '{"username":"testuser","password":"123456","nickname":"测试用户"}'
```

Expected: `{"success":true,"token":"eyJ...","username":"testuser","nickname":"测试用户"}`

- [ ] **Step 3: 测试登录**

```bash
curl -X POST http://localhost:8080/api/user/login -H "Content-Type: application/json" -d '{"username":"testuser","password":"123456"}'
```

Expected: `{"success":true,"token":"eyJ...","username":"testuser","nickname":"测试用户"}`

- [ ] **Step 4: 测试对话 API（用登录返回的 token）**

```bash
TOKEN=<上一步返回的 token>
curl http://localhost:8080/api/conversation/create -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
curl http://localhost:8080/api/conversation/list -H "Authorization: Bearer $TOKEN"
```

Expected: 创建对话成功，列表返回刚创建的对话。

- [ ] **Step 5: 测试游客模式**

打开浏览器 `http://localhost:8080`，不登录，直接发送问题，应能正常对话。

- [ ] **Step 6: 测试登录模式**

点右上角登录按钮，注册/登录后，新建对话并发送问题。刷新页面后对话历史仍在。

- [ ] **Step 7: 验证数据库**

```bash
docker exec consultant-mysql mysql -uroot -proot123 -e "USE consultant; SELECT id,username,nickname FROM user; SELECT id,user_id,title FROM conversation; SELECT id,conversation_id,role,LEFT(content,50) FROM message;"
```

Expected: 能看到注册的用户、创建的对话、以及保存的消息记录。
