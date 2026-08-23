package com.tongji.recommendation.gorse;

import com.tongji.profile.event.UserProfileUpdatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.http.HttpClient;
import java.time.Duration;
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
        this(createDefaultRestTemplate(properties), properties);
    }

    public GorseClient(RestTemplate restTemplate, GorseProperties properties) {
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (restTemplate.getRequestFactory() instanceof SimpleClientHttpRequestFactory factory) {
            factory.setConnectTimeout(properties.getTimeoutMs());
            factory.setReadTimeout(properties.getTimeoutMs());
        }
    }

    private static RestTemplate createDefaultRestTemplate(GorseProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return new RestTemplate(requestFactory);
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

    /**
     * 获取指定知文的标签相似推荐。
     */
    public List<String> related(long postId, int count) {
        String url = UriComponentsBuilder.fromUriString(properties.getEndpoint())
                .pathSegment("api", "item-to-item", properties.getItemToItemName(), String.valueOf(postId))
                .queryParam("n", count)
                .build()
                .encode()
                .toUriString();
        GorseScoredItem[] response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                GorseScoredItem[].class
        ).getBody();
        if (response == null || response.length == 0) {
            return List.of();
        }
        return Arrays.stream(response)
                .map(GorseScoredItem::id)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 获取配置的非个性化热度榜，保留 Gorse 返回顺序。
     *
     * @param offset 从零开始的榜单偏移量，不允许为负数
     * @param count 返回条数，必须为正数
     * @return 热度榜知文 ID；Gorse 返回空响应时返回空集合
     */
    public List<String> trending(int offset, int count) {
        if (offset < 0 || count <= 0) {
            throw new IllegalArgumentException("offset must be non-negative and count must be positive");
        }
        String url = UriComponentsBuilder.fromUriString(properties.getEndpoint())
                .pathSegment("api", "non-personalized", properties.getNonPersonalizedName())
                .queryParam("offset", offset)
                .queryParam("n", count)
                .build()
                .encode()
                .toUriString();
        GorseScoredItem[] response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers()),
                GorseScoredItem[].class
        ).getBody();
        if (response == null || response.length == 0) {
            return List.of();
        }
        return Arrays.stream(response)
                .map(GorseScoredItem::id)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 插入或更新 Gorse 知文物料，标签用于无模型训练的相似度计算。
     */
    public void upsertItem(GorseItemInput item) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Timestamp", item.publishedAt().toString());
        body.put("Labels", Map.of(
                "topics", item.topics(),
                "author_id", String.valueOf(item.authorId())
        ));
        body.put("Categories", item.topics());
        body.put("Comment", item.title());
        body.put("IsHidden", false);
        if (hasItem(item.postId())) {
            restTemplate.exchange(
                    properties.getEndpoint() + "/api/item/" + item.postId(),
                    HttpMethod.PATCH,
                    jsonEntity(body),
                    Void.class
            );
            return;
        }
        body.put("ItemId", String.valueOf(item.postId()));
        restTemplate.exchange(
                properties.getEndpoint() + "/api/item",
                HttpMethod.POST,
                jsonEntity(body),
                Void.class
        );
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
        body.put("Value", 1);
        restTemplate.exchange(
                properties.getEndpoint() + "/api/feedback",
                HttpMethod.PUT,
                jsonEntity(List.of(body)),
                Void.class
        );
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

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
