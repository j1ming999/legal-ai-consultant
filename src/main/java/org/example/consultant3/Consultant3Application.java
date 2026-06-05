package org.example.consultant3;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;

@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class, SecurityAutoConfiguration.class})
@MapperScan("org.example.consultant3.mapper")
public class Consultant3Application {
    public static void main(String[] args) {
        SpringApplication.run(Consultant3Application.class, args);
    }
}

