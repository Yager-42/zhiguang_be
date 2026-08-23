package com.tongji.recommendation.gorse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "recommendation.gorse")
@Data
public class GorseProperties {

    private String endpoint = "http://localhost:8087";
    private String apiKey = "";
    private int timeoutMs = 300;
    private boolean enabled = false;
    private String itemToItemName = "similar_topics";
    private String nonPersonalizedName = "trending";
}
