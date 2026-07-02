package com.tongji.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 注册全局 {@link RestTemplate} Bean。
 *
 * <p>Spring Boot 默认只自动装配 {@link RestTemplateBuilder}，不会注册 {@link RestTemplate} 实例；
 * 而 {@code CassandraTextStorageService} 等组件通过构造器注入 {@link RestTemplate}，
 * 因此在此显式声明一个 Bean 供容器注入。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }
}
