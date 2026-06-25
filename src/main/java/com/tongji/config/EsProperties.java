package com.tongji.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class EsProperties {
    private List<String> uris;
    private String username;
    private String password;

    public String getHost() {
        return (uris == null || uris.isEmpty()) ? null : uris.getFirst();
    }
}
