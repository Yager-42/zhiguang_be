package com.tongji.recommendation.gorse;

import com.tongji.profile.event.UserProfileUpdatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class GorseClient {

    private final RestTemplate restTemplate;
    private final GorseProperties properties;

    @Autowired
    public GorseClient(GorseProperties properties) {
        this(new RestTemplate(), properties);
    }

    public GorseClient(RestTemplate restTemplate, GorseProperties properties) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory factory) {
            factory.setConnectTimeout(properties.getTimeoutMs());
            factory.setReadTimeout(properties.getTimeoutMs());
        }
    }

    public List<String> recommend(long userId, int count) {
        String url = properties.getEndpoint() + "/api/recommend/" + userId + "?n=" + count;
        String[] response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                String[].class
        ).getBody();
        if (response == null || response.length == 0) {
            return List.of();
        }
        return Arrays.asList(response);
    }

    public void upsertItem(long postId, long authorId, Instant publishedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ItemId", String.valueOf(postId));
        body.put("Timestamp", publishedAt.toString());
        body.put("Labels", List.of(String.valueOf(authorId)));
        restTemplate.exchange(properties.getEndpoint() + "/api/item", HttpMethod.POST, jsonEntity(body), Void.class);
    }

    public boolean hasItem(long postId) {
        try {
            return restTemplate.exchange(
                    properties.getEndpoint() + "/api/item/" + postId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers()),
                    Map.class
            ).getStatusCode().is2xxSuccessful();
        } catch (HttpClientErrorException.NotFound notFound) {
            return false;
        }
    }

    public void insertFeedback(String feedbackType, long userId, String itemId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("FeedbackType", feedbackType);
        body.put("UserId", String.valueOf(userId));
        body.put("ItemId", itemId);
        body.put("Timestamp", Instant.now().toString());
        restTemplate.exchange(properties.getEndpoint() + "/api/feedback", HttpMethod.PUT, jsonEntity(body), Void.class);
    }

    public void upsertUser(UserProfileUpdatedEvent event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("UserId", String.valueOf(event.userId()));
        body.put("Labels", List.of(
                valueOrEmpty(event.nickname()),
                valueOrEmpty(event.avatar()),
                valueOrEmpty(event.bio()),
                valueOrEmpty(event.zgId()),
                valueOrEmpty(event.gender()),
                event.birthday() == null ? "" : event.birthday().toString(),
                valueOrEmpty(event.school()),
                valueOrEmpty(event.phone()),
                valueOrEmpty(event.email()),
                valueOrEmpty(event.tagJson())
        ));
        restTemplate.exchange(properties.getEndpoint() + "/api/user", HttpMethod.POST, jsonEntity(body), Void.class);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set("X-API-Key", properties.getApiKey());
        }
        return headers;
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
