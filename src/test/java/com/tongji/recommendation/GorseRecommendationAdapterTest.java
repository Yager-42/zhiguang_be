package com.tongji.recommendation;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GorseRecommendationAdapterTest {

    @Mock
    private KnowPostMapper knowPostMapper;

    @Test
    void recommendReturnsGorseCandidatesWhenEnabled() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        restTemplate.setResponse(List.of("101", "202"));
        GorseRecommendationAdapter adapter = new GorseRecommendationAdapter(
                knowPostMapper,
                new GorseClient(restTemplate, properties(true)),
                properties(true)
        );

        List<RecommendationCandidate> result = adapter.recommend(42L, 2);

        assertThat(result).containsExactly(
                new RecommendationCandidate(101L, "gorse", 2.0),
                new RecommendationCandidate(202L, "gorse", 1.0)
        );
        assertThat(restTemplate.getInvocationCount()).isEqualTo(1);
        verifyNoInteractions(knowPostMapper);
    }

    @Test
    void recommendReturnsHotFallbackWhenDisabled() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        when(knowPostMapper.listFeedPublicIds(3, 0)).thenReturn(List.of(301L, 302L));
        GorseRecommendationAdapter adapter = new GorseRecommendationAdapter(
                knowPostMapper,
                new GorseClient(restTemplate, properties(false)),
                properties(false)
        );

        List<RecommendationCandidate> result = adapter.recommend(42L, 3);

        assertThat(result).containsExactly(
                new RecommendationCandidate(301L, "hot", 3.0),
                new RecommendationCandidate(302L, "hot", 2.0)
        );
        assertThat(restTemplate.getInvocationCount()).isZero();
        verify(knowPostMapper).listFeedPublicIds(3, 0);
        verify(knowPostMapper, never()).listFeedPublic(3, 0);
    }

    @Test
    void recommendFallsBackToHotWhenGorseIsUnavailable() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        restTemplate.setException(new RestClientException("gorse down"));
        when(knowPostMapper.listFeedPublicIds(2, 0)).thenReturn(List.of(401L, 402L));
        GorseRecommendationAdapter adapter = new GorseRecommendationAdapter(
                knowPostMapper,
                new GorseClient(restTemplate, properties(true)),
                properties(true)
        );

        List<RecommendationCandidate> result = adapter.recommend(42L, 2);

        assertThat(result).containsExactly(
                new RecommendationCandidate(401L, "hot", 2.0),
                new RecommendationCandidate(402L, "hot", 1.0)
        );
        assertThat(restTemplate.getInvocationCount()).isEqualTo(1);
        verify(knowPostMapper).listFeedPublicIds(2, 0);
    }

    @Test
    void recommendPropagatesMalformedGorseIds() {
        StubRestTemplate restTemplate = new StubRestTemplate();
        restTemplate.setResponse(List.of("bad-id"));
        GorseRecommendationAdapter adapter = new GorseRecommendationAdapter(
                knowPostMapper,
                new GorseClient(restTemplate, properties(true)),
                properties(true)
        );

        assertThatThrownBy(() -> adapter.recommend(42L, 1))
                .isInstanceOf(NumberFormatException.class);
        verifyNoInteractions(knowPostMapper);
    }

    @Test
    void feedbackWritesUsePutForIdempotentReplay() {
        RecordingRestTemplate restTemplate = new RecordingRestTemplate();
        GorseClient client = new GorseClient(restTemplate, properties(true));

        client.insertFeedback("like", 42L, "101");

        assertThat(restTemplate.getLastMethod()).isEqualTo(HttpMethod.PUT);
        assertThat(restTemplate.getLastUrl()).endsWith("/api/feedback");
    }

    private GorseProperties properties(boolean enabled) {
        GorseProperties properties = new GorseProperties();
        properties.setEnabled(enabled);
        properties.setEndpoint("http://localhost:8087");
        properties.setApiKey("secret");
        properties.setTimeoutMs(300);
        return properties;
    }

    private static final class StubRestTemplate extends RestTemplate {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private List<String> response = List.of();
        private RuntimeException exception;

        void setResponse(List<String> response) {
            this.response = response;
        }

        void setException(RuntimeException exception) {
            this.exception = exception;
        }

        int getInvocationCount() {
            return invocationCount.get();
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType, Object... uriVariables) throws RestClientException {
            invocationCount.incrementAndGet();
            if (exception != null) {
                throw exception;
            }
            return ResponseEntity.ok(responseType.cast(response.toArray(String[]::new)));
        }
    }

    private static final class RecordingRestTemplate extends RestTemplate {

        private HttpMethod lastMethod;
        private String lastUrl;

        HttpMethod getLastMethod() {
            return lastMethod;
        }

        String getLastUrl() {
            return lastUrl;
        }

        @Override
        public <T> ResponseEntity<T> exchange(String url, HttpMethod method, HttpEntity<?> requestEntity, Class<T> responseType, Object... uriVariables) throws RestClientException {
            this.lastUrl = url;
            this.lastMethod = method;
            return ResponseEntity.ok(null);
        }
    }
}
