package com.tongji.recommendation.consumer;

import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GorseFeedbackRecommendationConsumerContractTest {

    @Test
    void feedbackWritesUsePutForReplaySafeOverwrite() {
        RecordingRestTemplate restTemplate = new RecordingRestTemplate();
        GorseClient client = new GorseClient(restTemplate, properties());

        client.insertFeedback("like", 42L, "101");

        assertThat(restTemplate.lastMethod).isEqualTo(HttpMethod.PUT);
        assertThat(restTemplate.lastUrl).endsWith("/api/feedback");
    }

    @Test
    void itemExistenceChecksUseGetOnItemResource() {
        RecordingRestTemplate restTemplate = new RecordingRestTemplate();
        GorseClient client = new GorseClient(restTemplate, properties());

        client.hasItem(101L);

        assertThat(restTemplate.lastMethod).isEqualTo(HttpMethod.GET);
        assertThat(restTemplate.lastUrl).endsWith("/api/item/101");
    }

    private GorseProperties properties() {
        GorseProperties properties = new GorseProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://localhost:8087");
        properties.setApiKey("secret");
        properties.setTimeoutMs(300);
        return properties;
    }

    private static final class RecordingRestTemplate extends RestTemplate {

        private HttpMethod lastMethod;
        private String lastUrl;

        @Override
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType, Object... uriVariables) throws RestClientException {
            this.lastUrl = url;
            this.lastMethod = method;
            return ResponseEntity.ok(null);
        }
    }
}
