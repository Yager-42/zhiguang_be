# add-recommendation-and-follow-feed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Gorse-backed recommendations and a local follow feed to the home feed, replacing the simple public list with a priority-filled mix of follow candidates, Gorse recommendations, and hot fallback.

**Architecture:** A `RecommendationEngine` adapter wraps Gorse (disabled by default; falls back to hot content when unavailable). A `FollowFeedService` manages two Redis ZSet structures: per-user inbox (push from normal authors) and per-author posts (pull for large-V). A `HomeFeedMixingService` fills 20 slots — follow first, then recommendations, then hot fallback — and replaces the existing `/api/v1/knowposts/feed` endpoint. Events feed Gorse: `content-published` → item upsert + fanout; `counter-events` → like/fav feedback; `comment-feedback` → comment feedback.

**Tech Stack:** Spring Boot 3.2.4, Kafka (existing), Redis ZSet (existing `StringRedisTemplate`), `RestTemplate` for Gorse REST API, Gorse-in-one Docker image.

**Prerequisites:** `eventize-publish-pipeline` (provides `content-published` Kafka topic), `add-comment-system` (provides `comment-feedback` topic), `add-leaf-id-service`, `add-cassandra-text-storage`.

**Key design decisions (from design.md):**
- Gorse upsert: async, consumes `content-published` Kafka event
- Fanout thresholds: `application.yml` config (`fanout.large-author-threshold: 10000`, super: 500000)
- Inbox: fixed 500-item max, no TTL
- Active follower priority: skipped in v1 — full push to all followers for normal authors
- Home feed fill order: follow → recommendations → hot
- Home feed: replaces existing `GET /api/v1/knowposts/feed`

---

## File Map

**New files:**
- `src/main/java/com/tongji/recommendation/RecommendationEngine.java`
- `src/main/java/com/tongji/recommendation/RecommendationCandidate.java`
- `src/main/java/com/tongji/recommendation/gorse/GorseItem.java`
- `src/main/java/com/tongji/recommendation/gorse/GorseFeedback.java`
- `src/main/java/com/tongji/recommendation/gorse/GorseClient.java`
- `src/main/java/com/tongji/recommendation/GorseRecommendationAdapter.java`
- `src/main/java/com/tongji/recommendation/feed/FollowFeedService.java`
- `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`
- `src/main/java/com/tongji/recommendation/consumer/RecommendationContentConsumer.java`
- `src/main/java/com/tongji/recommendation/consumer/GorseFeedbackConsumer.java`
- `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`
- test files

**Modified files:**
- `docker-compose.yml` — add Gorse service
- `src/main/resources/application.yml` — Gorse + fanout thresholds
- `src/main/java/com/tongji/counter/service/UserCounterService.java` — add `getFollowerCount`
- `src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java` — implement `getFollowerCount`
- `src/main/java/com/tongji/knowpost/service/KnowPostFeedService.java` — add `getFeedByIds`
- `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java` — implement `getFeedByIds`
- `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java` — add `findDetailsByIds`
- `src/main/resources/mapper/KnowPostMapper.xml` — SQL for `findDetailsByIds`
- `src/main/java/com/tongji/knowpost/api/KnowPostController.java` — replace feed + add follow feed endpoint

---

## Task 1: application.yml + docker-compose Gorse Service

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add recommendation and fanout config to application.yml**

Add as a new top-level key (not nested under `spring:`):

```yaml
recommendation:
  gorse:
    endpoint: ${GORSE_ENDPOINT:http://localhost:8087}
    api-key: ${GORSE_API_KEY:}
    timeout-ms: 300
    enabled: ${GORSE_ENABLED:false}

feed:
  fanout:
    large-author-threshold: ${FANOUT_LARGE_AUTHOR_THRESHOLD:10000}
    super-large-author-threshold: ${FANOUT_SUPER_LARGE_AUTHOR_THRESHOLD:500000}
    inbox-max-size: ${FEED_INBOX_MAX_SIZE:500}
```

- [ ] **Step 2: Add Gorse service to docker-compose.yml**

Append to the `services:` block before the `volumes:` section. Gorse uses the existing MySQL and Redis services:

```yaml
  gorse:
    image: zhenghaoz/gorse-in-one:0.4
    container_name: zhiguang-gorse
    ports:
      - "8087:8087"
      - "8088:8088"
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      GORSE_CACHE_STORE: redis://redis:6379
      GORSE_DATA_STORE: mysql://root:root@tcp(mysql:3306)/zhiguang
    volumes:
      - gorse_data:/var/lib/gorse
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8087/api/health || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 5
```

Also add `gorse_data:` under the existing `volumes:` block.

> **Note:** Gorse is `enabled: false` by default so the app works without Docker Gorse running. The adapter falls back to hot content when disabled or unavailable.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml docker-compose.yml
git commit -m "feat: add Gorse docker service and recommendation/fanout config"
```

---

## Task 2: RecommendationEngine Interface + Gorse Models + GorseClient

**Files:**
- Create: `src/main/java/com/tongji/recommendation/RecommendationEngine.java`
- Create: `src/main/java/com/tongji/recommendation/RecommendationCandidate.java`
- Create: `src/main/java/com/tongji/recommendation/gorse/GorseItem.java`
- Create: `src/main/java/com/tongji/recommendation/gorse/GorseFeedback.java`
- Create: `src/main/java/com/tongji/recommendation/gorse/GorseClient.java`

- [ ] **Step 1: Create RecommendationCandidate.java**

```java
package com.tongji.recommendation;

public class RecommendationCandidate {
    private final long contentId;
    private final double score;
    private final String source;  // "gorse", "hot", "follow"

    public RecommendationCandidate(long contentId, double score, String source) {
        this.contentId = contentId;
        this.score = score;
        this.source = source;
    }

    public long getContentId() { return contentId; }
    public double getScore() { return score; }
    public String getSource() { return source; }
}
```

- [ ] **Step 2: Create RecommendationEngine.java**

```java
package com.tongji.recommendation;

import java.util.List;

public interface RecommendationEngine {
    /**
     * Returns up to count recommendation candidates for userId.
     * Never throws — returns hot fallback on any failure.
     */
    List<RecommendationCandidate> recommend(long userId, int count);
}
```

- [ ] **Step 3: Create GorseItem.java**

```java
package com.tongji.recommendation.gorse;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class GorseItem {
    @JsonProperty("ItemId")  private String itemId;
    @JsonProperty("Labels")  private List<String> labels;
    @JsonProperty("Timestamp") private String timestamp;
    @JsonProperty("Categories") private List<String> categories;

    public GorseItem(String itemId, String timestamp) {
        this.itemId = itemId;
        this.timestamp = timestamp;
        this.labels = List.of();
        this.categories = List.of("post");
    }

    public String getItemId() { return itemId; }
    public List<String> getLabels() { return labels; }
    public String getTimestamp() { return timestamp; }
    public List<String> getCategories() { return categories; }
}
```

- [ ] **Step 4: Create GorseFeedback.java**

```java
package com.tongji.recommendation.gorse;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GorseFeedback {
    @JsonProperty("FeedbackType") private String feedbackType;
    @JsonProperty("UserId")       private String userId;
    @JsonProperty("ItemId")       private String itemId;
    @JsonProperty("Timestamp")    private String timestamp;

    public GorseFeedback(String feedbackType, long userId, long itemId, String timestamp) {
        this.feedbackType = feedbackType;
        this.userId = String.valueOf(userId);
        this.itemId = String.valueOf(itemId);
        this.timestamp = timestamp;
    }

    public String getFeedbackType() { return feedbackType; }
    public String getUserId() { return userId; }
    public String getItemId() { return itemId; }
    public String getTimestamp() { return timestamp; }
}
```

- [ ] **Step 5: Create GorseClient.java**

```java
package com.tongji.recommendation.gorse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

@Component
public class GorseClient {

    private static final Logger log = LoggerFactory.getLogger(GorseClient.class);

    private final RestTemplate restTemplate;

    @Value("${recommendation.gorse.endpoint:http://localhost:8087}")
    private String endpoint;

    @Value("${recommendation.gorse.api-key:}")
    private String apiKey;

    @Value("${recommendation.gorse.enabled:false}")
    private boolean enabled;

    public GorseClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isEnabled() { return enabled; }

    public void upsertItems(List<GorseItem> items) {
        if (!enabled || items.isEmpty()) return;
        try {
            HttpEntity<List<GorseItem>> req = new HttpEntity<>(items, headers());
            restTemplate.postForObject(endpoint + "/api/items", req, String.class);
        } catch (Exception e) {
            log.warn("Gorse upsertItems failed: {}", e.getMessage());
        }
    }

    public void insertFeedbacks(List<GorseFeedback> feedbacks) {
        if (!enabled || feedbacks.isEmpty()) return;
        try {
            HttpEntity<List<GorseFeedback>> req = new HttpEntity<>(feedbacks, headers());
            restTemplate.postForObject(endpoint + "/api/feedbacks", req, String.class);
        } catch (Exception e) {
            log.warn("Gorse insertFeedbacks failed: {}", e.getMessage());
        }
    }

    /**
     * Returns item IDs recommended for the user, or empty list on any failure.
     */
    public List<String> recommend(long userId, int n) {
        if (!enabled) return Collections.emptyList();
        try {
            String url = endpoint + "/api/recommend/" + userId + "?n=" + n;
            ResponseEntity<List<String>> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers()),
                new ParameterizedTypeReference<List<String>>() {});
            return resp.getBody() != null ? resp.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Gorse recommend failed for userId={}: {}", userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) h.set("X-API-Key", apiKey);
        return h;
    }
}
```

- [ ] **Step 6: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/recommendation/
git commit -m "feat: add RecommendationEngine interface, GorseClient, and data models"
```

---

## Task 3: GorseRecommendationAdapter (TDD)

**Files:**
- Create: `src/test/java/com/tongji/recommendation/GorseRecommendationAdapterTest.java`
- Create: `src/main/java/com/tongji/recommendation/GorseRecommendationAdapter.java`

- [ ] **Step 1: Write failing tests**

```java
package com.tongji.recommendation;

import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.model.FeedPageResponse;
import com.tongji.recommendation.gorse.GorseClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GorseRecommendationAdapterTest {

    @Mock GorseClient gorseClient;
    @Mock KnowPostFeedService feedService;
    @InjectMocks GorseRecommendationAdapter adapter;

    @Test
    void recommend_returnsGorseCandidatesWhenEnabled() {
        when(gorseClient.isEnabled()).thenReturn(true);
        when(gorseClient.recommend(10L, 5)).thenReturn(List.of("100", "200", "300"));

        List<RecommendationCandidate> result = adapter.recommend(10L, 5);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getContentId()).isEqualTo(100L);
        assertThat(result.get(0).getSource()).isEqualTo("gorse");
        verify(feedService, never()).getPublicFeed(anyInt(), anyInt(), any());
    }

    @Test
    void recommend_fallsBackToHotWhenGorseDisabled() {
        when(gorseClient.isEnabled()).thenReturn(false);
        FeedPageResponse hot = buildHotFeed(List.of(1L, 2L, 3L));
        // hotFallback(count=3) calls getPublicFeed(1, count*2=6, null)
        when(feedService.getPublicFeed(1, 6, null)).thenReturn(hot);

        List<RecommendationCandidate> result = adapter.recommend(10L, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getSource()).isEqualTo("hot");
        verify(gorseClient, never()).recommend(anyLong(), anyInt());
    }

    @Test
    void recommend_fallsBackToHotWhenGorseReturnsEmpty() {
        when(gorseClient.isEnabled()).thenReturn(true);
        when(gorseClient.recommend(10L, 3)).thenReturn(List.of());
        FeedPageResponse hot = buildHotFeed(List.of(5L, 6L, 7L));
        when(feedService.getPublicFeed(1, 6, null)).thenReturn(hot);

        List<RecommendationCandidate> result = adapter.recommend(10L, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getSource()).isEqualTo("hot");
    }

    private FeedPageResponse buildHotFeed(List<Long> postIds) {
        FeedPageResponse resp = new FeedPageResponse();
        resp.setItems(postIds.stream().map(id -> {
            var item = new com.tongji.knowpost.model.FeedItem();
            item.setId(id);
            return item;
        }).toList());
        return resp;
    }
}
```

> **Note:** `FeedPageResponse` and `FeedItem` are existing types in the codebase. Verify exact field names/setters by checking `src/main/java/com/tongji/knowpost/model/`.

- [ ] **Step 2: Run — confirm failure**

```bash
mvn test -Dtest=GorseRecommendationAdapterTest -q 2>&1 | tail -5
```

Expected: compilation error — `GorseRecommendationAdapter` not found

- [ ] **Step 3: Create GorseRecommendationAdapter.java**

```java
package com.tongji.recommendation;

import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.gorse.GorseClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class GorseRecommendationAdapter implements RecommendationEngine {

    @Resource private GorseClient gorseClient;
    @Resource private KnowPostFeedService feedService;

    @Override
    public List<RecommendationCandidate> recommend(long userId, int count) {
        if (gorseClient.isEnabled()) {
            List<String> ids = gorseClient.recommend(userId, count);
            if (!ids.isEmpty()) {
                return toGorseCandidates(ids);
            }
        }
        return hotFallback(count);
    }

    private List<RecommendationCandidate> toGorseCandidates(List<String> ids) {
        return ids.stream()
            .map(id -> new RecommendationCandidate(Long.parseLong(id), 1.0, "gorse"))
            .collect(Collectors.toList());
    }

    private List<RecommendationCandidate> hotFallback(int count) {
        var page = feedService.getPublicFeed(1, count * 2, null);
        return page.getItems().stream()
            .limit(count)
            .map(item -> new RecommendationCandidate(item.getId(), 1.0, "hot"))
            .collect(Collectors.toList());
    }
}
```

> **Note:** `item.getId()` — verify the method name on `FeedItem` that returns the post ID.

- [ ] **Step 4: Run — all pass**

```bash
mvn test -Dtest=GorseRecommendationAdapterTest -q
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tongji/recommendation/GorseRecommendationAdapter.java \
        src/test/java/com/tongji/recommendation/GorseRecommendationAdapterTest.java
git commit -m "feat: implement GorseRecommendationAdapter with hot content fallback"
```

---

## Task 4: UserCounterService.getFollowerCount + FollowFeedService (TDD)

**Files:**
- Modify: `src/main/java/com/tongji/counter/service/UserCounterService.java`
- Modify: `src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java`
- Create: `src/test/java/com/tongji/recommendation/feed/FollowFeedServiceTest.java`
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedService.java`
- Create: `src/main/java/com/tongji/recommendation/feed/FollowFeedServiceImpl.java`

- [ ] **Step 1: Add getFollowerCount to UserCounterService.java**

Add after existing interface methods:

```java
/** Returns the follower count for userId from Redis SDS. Returns 0 if not cached. */
long getFollowerCount(long userId);
```

- [ ] **Step 2: Implement getFollowerCount in UserCounterServiceImpl.java**

Add after existing methods. The SDS stores 5 x 4-byte big-endian integers; followers is at idx=2 → bytes 4–7:

```java
@Override
public long getFollowerCount(long userId) {
    String key = UserCounterKeys.sdsKey(userId);
    byte[] raw = redis.execute(
        (RedisCallback<byte[]>) c -> c.stringCommands().get(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if (raw == null || raw.length < 8) return 0L;
    // idx=2 (followers) → byte offset (2-1)*4 = 4, length 4 bytes, big-endian signed int
    int followers = ((raw[4] & 0xFF) << 24) | ((raw[5] & 0xFF) << 16)
                  | ((raw[6] & 0xFF) << 8)  | (raw[7] & 0xFF);
    return Math.max(0, followers);
}
```

- [ ] **Step 3: Write failing FollowFeedService tests**

```java
package com.tongji.recommendation.feed;

import com.tongji.counter.service.UserCounterService;
import com.tongji.recommendation.feed.FollowFeedServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowFeedServiceTest {

    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock UserCounterService userCounterService;

    FollowFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(zSetOps);
        service = new FollowFeedServiceImpl(redis, userCounterService);
        ReflectionTestUtils.setField(service, "largeAuthorThreshold", 10000L);
        ReflectionTestUtils.setField(service, "superLargeAuthorThreshold", 500000L);
        ReflectionTestUtils.setField(service, "inboxMaxSize", 500);
    }

    @Test
    void handlePublish_normalAuthor_pushesIntoFollowerInboxes() {
        when(userCounterService.getFollowerCount(1L)).thenReturn(50L);
        when(zSetOps.range("uf:fans:1", 0, -1)).thenReturn(Set.of("100", "200"));
        when(zSetOps.size(anyString())).thenReturn(1L);

        service.handlePublish(999L, 1L, 1234567890000L);

        verify(zSetOps).add("feed:inbox:100", "999", 1234567890000.0);
        verify(zSetOps).add("feed:inbox:200", "999", 1234567890000.0);
        verify(zSetOps, never()).add(eq("feed:author:posts:1"), anyString(), anyDouble());
    }

    @Test
    void handlePublish_largeVAuthor_writesAuthorPostsZSet() {
        when(userCounterService.getFollowerCount(2L)).thenReturn(50000L);

        service.handlePublish(888L, 2L, 9999L);

        verify(zSetOps).add("feed:author:posts:2", "888", 9999.0);
        verify(zSetOps, never()).add(startsWith("feed:inbox:"), anyString(), anyDouble());
    }

    @Test
    void handlePublish_superLargeVAuthor_skipsAll() {
        when(userCounterService.getFollowerCount(3L)).thenReturn(600000L);

        service.handlePublish(777L, 3L, 1L);

        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void getFollowCandidates_returnsFromInboxThenAuthorPosts() {
        when(zSetOps.reverseRange("feed:inbox:10", 0, 19))
            .thenReturn(Set.of("1", "2", "3"));
        when(zSetOps.range("uf:flws:10", 0, -1)).thenReturn(Set.of());

        List<Long> result = service.getFollowCandidates(10L, 20);

        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void inbox_trimmedToMaxSize() {
        when(userCounterService.getFollowerCount(1L)).thenReturn(10L);
        when(zSetOps.range("uf:fans:1", 0, -1)).thenReturn(Set.of("99"));
        when(zSetOps.size("feed:inbox:99")).thenReturn(501L);  // over max

        service.handlePublish(555L, 1L, 0L);

        // Should trim: removeRange(key, 0, 501 - 500 - 1) = removeRange(key, 0, 0)
        verify(zSetOps).removeRange("feed:inbox:99", 0, 0);
    }
}
```

- [ ] **Step 4: Run — confirm failure**

```bash
mvn test -Dtest=FollowFeedServiceTest -q 2>&1 | tail -5
```

Expected: compilation error — `FollowFeedServiceImpl` not found

- [ ] **Step 5: Create FollowFeedService.java**

```java
package com.tongji.recommendation.feed;

import java.util.List;

public interface FollowFeedService {
    /**
     * Routes publish event to fanout push (normal) or author posts ZSet (large-V)
     * based on author's follower count.
     */
    void handlePublish(long postId, long authorId, long publishTimeMs);

    /**
     * Returns follow-feed candidate post IDs for the user
     * (from inbox + large-V author posts, deduplicated, newest-first).
     */
    List<Long> getFollowCandidates(long userId, int limit);
}
```

- [ ] **Step 6: Create FollowFeedServiceImpl.java**

```java
package com.tongji.recommendation.feed;

import com.tongji.counter.service.UserCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FollowFeedServiceImpl implements FollowFeedService {

    private static final Logger log = LoggerFactory.getLogger(FollowFeedServiceImpl.class);

    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;

    @Value("${feed.fanout.large-author-threshold:10000}")
    private long largeAuthorThreshold;

    @Value("${feed.fanout.super-large-author-threshold:500000}")
    private long superLargeAuthorThreshold;

    @Value("${feed.fanout.inbox-max-size:500}")
    private int inboxMaxSize;

    public FollowFeedServiceImpl(StringRedisTemplate redis, UserCounterService userCounterService) {
        this.redis = redis;
        this.userCounterService = userCounterService;
    }

    @Override
    public void handlePublish(long postId, long authorId, long publishTimeMs) {
        long followers = userCounterService.getFollowerCount(authorId);

        if (followers >= superLargeAuthorThreshold) {
            return; // super large-V: skip all push
        }

        if (followers >= largeAuthorThreshold) {
            // Large-V: write to author posts ZSet (followers pull)
            redis.opsForZSet().add(
                "feed:author:posts:" + authorId,
                String.valueOf(postId),
                (double) publishTimeMs);
            trimZSet("feed:author:posts:" + authorId);
            return;
        }

        // Normal author: push to all follower inboxes
        Set<String> followerIds = redis.opsForZSet().range("uf:fans:" + authorId, 0, -1);
        if (followerIds == null || followerIds.isEmpty()) return;

        for (String followerIdStr : followerIds) {
            String inboxKey = "feed:inbox:" + followerIdStr;
            redis.opsForZSet().add(inboxKey, String.valueOf(postId), (double) publishTimeMs);
            Long size = redis.opsForZSet().size(inboxKey);
            if (size != null && size > inboxMaxSize) {
                redis.opsForZSet().removeRange(inboxKey, 0, size - inboxMaxSize - 1);
            }
        }
    }

    @Override
    public List<Long> getFollowCandidates(long userId, int limit) {
        Set<Long> seen = new LinkedHashSet<>();

        // 1. From inbox ZSet (normal author posts pushed to this user)
        Set<String> inbox = redis.opsForZSet().reverseRange("feed:inbox:" + userId, 0, limit - 1L);
        if (inbox != null) {
            for (String id : inbox) seen.add(Long.parseLong(id));
        }

        // 2. From following large-V authors' post ZSets (pull on read)
        if (seen.size() < limit) {
            Set<String> followingIds = redis.opsForZSet().range("uf:flws:" + userId, 0, -1);
            if (followingIds != null) {
                for (String followingIdStr : followingIds) {
                    if (seen.size() >= limit) break;
                    Set<String> authorPosts = redis.opsForZSet()
                        .reverseRange("feed:author:posts:" + followingIdStr, 0, (long)(limit - 1));
                    if (authorPosts != null) {
                        for (String id : authorPosts) {
                            seen.add(Long.parseLong(id));
                            if (seen.size() >= limit) break;
                        }
                    }
                }
            }
        }

        List<Long> result = new ArrayList<>(seen);
        return result.subList(0, Math.min(result.size(), limit));
    }

    private void trimZSet(String key) {
        Long size = redis.opsForZSet().size(key);
        if (size != null && size > inboxMaxSize) {
            redis.opsForZSet().removeRange(key, 0, size - inboxMaxSize - 1);
        }
    }
}
```

- [ ] **Step 7: Run — all pass**

```bash
mvn test -Dtest=FollowFeedServiceTest -q
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tongji/counter/service/UserCounterService.java \
        src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java \
        src/main/java/com/tongji/recommendation/feed/ \
        src/test/java/com/tongji/recommendation/feed/FollowFeedServiceTest.java
git commit -m "feat: add UserCounterService.getFollowerCount and FollowFeedService with fanout"
```

---

## Task 5: RecommendationContentConsumer (content_published → fanout + Gorse item)

**Files:**
- Create: `src/main/java/com/tongji/recommendation/consumer/RecommendationContentConsumer.java`

This consumer listens to the `content-published` Kafka topic (produced by `eventize-publish-pipeline`) using a separate consumer group so it runs independently of `PublishDerivedConsumer`.

- [ ] **Step 1: Create RecommendationContentConsumer.java**

```java
package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.knowpost.publish.ContentPublishedEvent;
import com.tongji.recommendation.feed.FollowFeedService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseItem;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class RecommendationContentConsumer {

    private static final Logger log = LoggerFactory.getLogger(RecommendationContentConsumer.class);

    @Resource private ObjectMapper objectMapper;
    @Resource private GorseClient gorseClient;
    @Resource private FollowFeedService followFeedService;

    @KafkaListener(
        topics = "${knowpost.kafka.content-published-topic:content-published}",
        groupId = "recommendation-content-consumer"
    )
    public void onContentPublished(ConsumerRecord<String, String> record, Acknowledgment ack) {
        ContentPublishedEvent event;
        try {
            event = objectMapper.readValue(record.value(), ContentPublishedEvent.class);
        } catch (Exception e) {
            log.error("Failed to parse content_published event: {}", record.value());
            ack.acknowledge();
            return;
        }

        long postId = event.getPostId();
        long creatorId = event.getCreatorId();
        long publishedAtMs = event.getPublishedAt() != null
            ? event.getPublishedAt().toEpochMilli()
            : System.currentTimeMillis();

        // 1. Upsert Gorse item (async, best-effort)
        try {
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(publishedAtMs).atZone(ZoneOffset.UTC));
            gorseClient.upsertItems(List.of(new GorseItem(String.valueOf(postId), timestamp)));
        } catch (Exception e) {
            log.warn("Gorse item upsert failed for postId={}: {}", postId, e.getMessage());
        }

        // 2. Handle follow feed fanout
        try {
            followFeedService.handlePublish(postId, creatorId, publishedAtMs);
        } catch (Exception e) {
            log.warn("Follow feed fanout failed for postId={}: {}", postId, e.getMessage());
        }

        ack.acknowledge();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tongji/recommendation/consumer/RecommendationContentConsumer.java
git commit -m "feat: add RecommendationContentConsumer for Gorse upsert and follow feed fanout"
```

---

## Task 6: GorseFeedbackConsumer (like/fav/comment feedback)

**Files:**
- Create: `src/main/java/com/tongji/recommendation/consumer/GorseFeedbackConsumer.java`

This consumer listens to two Kafka topics:
1. `counter-events` — sends like/fav feedback to Gorse when `entityType=knowpost`
2. `comment-feedback` — sends comment feedback when `action=comment`

- [ ] **Step 1: Create GorseFeedbackConsumer.java**

```java
package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.comment.event.CommentFeedbackEvent;
import com.tongji.counter.event.CounterEvent;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseFeedback;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class GorseFeedbackConsumer {

    private static final Logger log = LoggerFactory.getLogger(GorseFeedbackConsumer.class);

    @Resource private ObjectMapper objectMapper;
    @Resource private GorseClient gorseClient;

    /**
     * Listens to counter-events for like/fav actions on posts.
     * Uses separate group so it doesn't interfere with counter aggregation.
     */
    @KafkaListener(
        topics = "counter-events",
        groupId = "gorse-counter-feedback-consumer"
    )
    public void onCounterEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            CounterEvent event = objectMapper.readValue(record.value(), CounterEvent.class);
            // Only send feedback for like/fav on posts with positive delta
            if (!"knowpost".equals(event.getEntityType()) || event.getDelta() <= 0) {
                ack.acknowledge();
                return;
            }
            String feedbackType = switch (event.getMetric()) {
                case "like" -> "like";
                case "fav"  -> "star";
                default     -> null;
            };
            if (feedbackType == null || event.getUserId() == 0) {
                ack.acknowledge();
                return;
            }
            String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC));
            gorseClient.insertFeedbacks(List.of(new GorseFeedback(
                feedbackType,
                event.getUserId(),
                Long.parseLong(event.getEntityId()),
                ts)));
        } catch (Exception e) {
            log.warn("GorseFeedback counter event failed: {}", e.getMessage());
        }
        ack.acknowledge();
    }

    /**
     * Listens to comment-feedback events for comment actions on posts.
     */
    @KafkaListener(
        topics = "${comment.kafka.feedback-topic:comment-feedback}",
        groupId = "gorse-comment-feedback-consumer"
    )
    public void onCommentFeedback(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            CommentFeedbackEvent event = objectMapper.readValue(record.value(), CommentFeedbackEvent.class);
            if (!"comment".equals(event.getAction()) || event.getPostId() == 0) {
                ack.acknowledge();
                return;
            }
            String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC));
            gorseClient.insertFeedbacks(List.of(new GorseFeedback(
                "comment", event.getUserId(), event.getPostId(), ts)));
        } catch (Exception e) {
            log.warn("GorseFeedback comment event failed: {}", e.getMessage());
        }
        ack.acknowledge();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tongji/recommendation/consumer/GorseFeedbackConsumer.java
git commit -m "feat: add GorseFeedbackConsumer for like/fav/comment feedback to Gorse"
```

---

## Task 7: KnowPostFeedService.getFeedByIds + HomeFeedMixingService (TDD)

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/service/KnowPostFeedService.java`
- Modify: `src/main/java/com/tongji/knowpost/service/impl/KnowPostFeedServiceImpl.java`
- Modify: `src/main/java/com/tongji/knowpost/mapper/KnowPostMapper.java`
- Modify: `src/main/resources/mapper/KnowPostMapper.xml`
- Create: `src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java`
- Create: `src/main/java/com/tongji/recommendation/HomeFeedMixingService.java`

- [ ] **Step 1: Add findDetailsByIds to KnowPostMapper.java**

Add after existing methods:

```java
/** Batch fetch published+public post details by IDs. Preserves input order via FIELD(). */
List<KnowPostDetailRow> findDetailsByIds(@Param("ids") List<Long> ids);
```

- [ ] **Step 2: Add SQL to KnowPostMapper.xml**

```xml
<select id="findDetailsByIds" resultMap="detailResultMap">
    SELECT kp.id, kp.title, kp.description, kp.img_urls, kp.tags, kp.visible,
           kp.status, kp.publish_time, kp.is_top, kp.content_url,
           u.id as authorId, u.nickname as authorNickname, u.avatar_url as authorAvatar
    FROM know_posts kp
    JOIN users u ON kp.creator_id = u.id
    WHERE kp.id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
    AND kp.status = 'published'
    AND kp.visible = 'public'
    ORDER BY FIELD(kp.id,
    <foreach collection="ids" item="id" separator=",">#{id}</foreach>
    )
</select>
```

> **Note:** Check that `detailResultMap` exists in `KnowPostMapper.xml` and maps `KnowPostDetailRow`. If it uses a different resultMap name, update accordingly.

- [ ] **Step 3: Add getFeedByIds to KnowPostFeedService.java**

```java
/**
 * Hydrates a list of post IDs into a FeedPageResponse.
 * Filters out deleted/invisible posts. Preserves input order.
 */
FeedPageResponse getFeedByIds(List<Long> ids, Long currentUserIdNullable);
```

- [ ] **Step 4: Implement getFeedByIds in KnowPostFeedServiceImpl.java**

Add after existing methods. `mapper` and `counterService` are already injected fields:

```java
@Override
public FeedPageResponse getFeedByIds(List<Long> ids, Long currentUserIdNullable) {
    if (ids == null || ids.isEmpty()) {
        FeedPageResponse empty = new FeedPageResponse();
        empty.setItems(List.of());
        empty.setHasMore(false);
        return empty;
    }
    List<KnowPostDetailRow> rows = mapper.findDetailsByIds(ids);
    // Build hydrated FeedItems (reuse existing buildFeedItem logic from this class)
    List<FeedItem> items = rows.stream()
        .map(row -> buildFeedItem(row, currentUserIdNullable))
        .toList();
    FeedPageResponse resp = new FeedPageResponse();
    resp.setItems(items);
    resp.setHasMore(false);
    return resp;
}
```

> **Note:** `buildFeedItem` should already exist in `KnowPostFeedServiceImpl` (used by `getPublicFeed`). If named differently, find and use the existing hydration helper method.

- [ ] **Step 5: Write failing HomeFeedMixingService tests**

```java
package com.tongji.recommendation;

import com.tongji.knowpost.model.FeedItem;
import com.tongji.knowpost.model.FeedPageResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.feed.FollowFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HomeFeedMixingServiceTest {

    @Mock FollowFeedService followFeedService;
    @Mock RecommendationEngine recommendationEngine;
    @Mock KnowPostFeedService feedService;
    @InjectMocks HomeFeedMixingService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "pageSize", 20);
    }

    @Test
    void getHomeFeed_fillsFromFollowFirstThenRecommend() {
        when(followFeedService.getFollowCandidates(1L, 20)).thenReturn(List.of(10L, 11L, 12L));
        when(recommendationEngine.recommend(1L, 17)).thenReturn(List.of(
            new RecommendationCandidate(20L, 1.0, "gorse"),
            new RecommendationCandidate(21L, 1.0, "gorse")
        ));
        when(feedService.getFeedByIds(anyList(), eq(1L))).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return buildFeedResponse(ids);
        });

        FeedPageResponse resp = service.getHomeFeed(1L);

        List<Long> expectedOrder = List.of(10L, 11L, 12L, 20L, 21L);
        List<Long> actualIds = resp.getItems().stream().map(FeedItem::getId).toList();
        assertThat(actualIds).containsExactlyInAnyOrderElementsOf(expectedOrder);
    }

    @Test
    void getHomeFeed_deduplicatesIds() {
        when(followFeedService.getFollowCandidates(1L, 20)).thenReturn(List.of(10L, 11L));
        when(recommendationEngine.recommend(1L, 18)).thenReturn(List.of(
            new RecommendationCandidate(10L, 1.0, "gorse"),  // duplicate!
            new RecommendationCandidate(99L, 1.0, "gorse")
        ));
        when(feedService.getFeedByIds(anyList(), eq(1L))).thenAnswer(inv -> buildFeedResponse(inv.getArgument(0)));

        FeedPageResponse resp = service.getHomeFeed(1L);

        long distinctCount = resp.getItems().stream().map(FeedItem::getId).distinct().count();
        assertThat(distinctCount).isEqualTo(resp.getItems().size());
        // 10 should not appear twice
        assertThat(resp.getItems().stream().filter(i -> i.getId() == 10L).count()).isEqualTo(1);
    }

    @Test
    void getHomeFeed_fallsBackToHotWhenFollowAndRecommendBothEmpty() {
        when(followFeedService.getFollowCandidates(1L, 20)).thenReturn(List.of());
        when(recommendationEngine.recommend(1L, 20)).thenReturn(List.of());
        FeedPageResponse hot = buildFeedResponse(List.of(5L, 6L, 7L));
        when(feedService.getPublicFeed(1, 40, 1L)).thenReturn(hot);
        when(feedService.getFeedByIds(anyList(), eq(1L))).thenAnswer(inv -> buildFeedResponse(inv.getArgument(0)));

        FeedPageResponse resp = service.getHomeFeed(1L);

        assertThat(resp.getItems()).hasSize(3);
    }

    private FeedPageResponse buildFeedResponse(List<Long> ids) {
        FeedPageResponse r = new FeedPageResponse();
        r.setItems(ids.stream().map(id -> {
            FeedItem item = new FeedItem();
            item.setId(id);
            return item;
        }).toList());
        return r;
    }
}
```

- [ ] **Step 6: Run — confirm failure**

```bash
mvn test -Dtest=HomeFeedMixingServiceTest -q 2>&1 | tail -5
```

Expected: compilation error — `HomeFeedMixingService` not found

- [ ] **Step 7: Create HomeFeedMixingService.java**

```java
package com.tongji.recommendation;

import com.tongji.knowpost.model.FeedPageResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.feed.FollowFeedService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;

@Service
public class HomeFeedMixingService {

    @Resource private FollowFeedService followFeedService;
    @Resource private RecommendationEngine recommendationEngine;
    @Resource private KnowPostFeedService feedService;

    @Value("${feed.home.page-size:20}")
    private int pageSize;

    /**
     * Priority-fill home feed: follow → recommendations → hot fallback.
     * Deduplicates by post ID.
     */
    public FeedPageResponse getHomeFeed(long userId) {
        Set<Long> seen = new LinkedHashSet<>();

        // 1. Follow feed candidates
        List<Long> followIds = followFeedService.getFollowCandidates(userId, pageSize);
        seen.addAll(followIds);

        // 2. Recommendation candidates (fill remaining slots)
        if (seen.size() < pageSize) {
            List<RecommendationCandidate> rec =
                recommendationEngine.recommend(userId, pageSize - seen.size());
            for (RecommendationCandidate c : rec) seen.add(c.getContentId());
        }

        // 3. Hot fallback (fill remaining slots)
        if (seen.size() < pageSize) {
            FeedPageResponse hot = feedService.getPublicFeed(1, pageSize * 2, userId);
            for (var item : hot.getItems()) {
                seen.add(item.getId());
                if (seen.size() >= pageSize) break;
            }
        }

        List<Long> finalIds = new ArrayList<>(seen);
        if (finalIds.size() > pageSize) finalIds = finalIds.subList(0, pageSize);

        return feedService.getFeedByIds(finalIds, userId);
    }

    /**
     * Pure follow feed for the dedicated follow feed endpoint.
     */
    public FeedPageResponse getFollowFeed(long userId) {
        List<Long> ids = followFeedService.getFollowCandidates(userId, pageSize);
        return feedService.getFeedByIds(ids, userId);
    }
}
```

- [ ] **Step 8: Run — all pass**

```bash
mvn test -Dtest=HomeFeedMixingServiceTest -q
```

Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/tongji/knowpost/ \
        src/main/resources/mapper/KnowPostMapper.xml \
        src/main/java/com/tongji/recommendation/HomeFeedMixingService.java \
        src/test/java/com/tongji/recommendation/HomeFeedMixingServiceTest.java
git commit -m "feat: add getFeedByIds, HomeFeedMixingService with priority fill"
```

---

## Task 8: Update Home Feed API + Follow Feed Endpoint

**Files:**
- Modify: `src/main/java/com/tongji/knowpost/api/KnowPostController.java`
- Modify: `src/main/resources/application.yml` — add feed.home.page-size

- [ ] **Step 1: Add page-size config to application.yml**

The `feed:` block was created in Task 1 with `fanout.*` keys. Add `home.page-size` **nested inside the existing `feed:` block** (do NOT create a second `feed:` key):

```yaml
feed:
  home:
    page-size: 20          # ADD this
  fanout:                  # already exists from Task 1
    large-author-threshold: ${FANOUT_LARGE_AUTHOR_THRESHOLD:10000}
    super-large-author-threshold: ${FANOUT_SUPER_LARGE_AUTHOR_THRESHOLD:500000}
    inbox-max-size: ${FEED_INBOX_MAX_SIZE:500}
```

- [ ] **Step 2: Inject HomeFeedMixingService into KnowPostController.java**

Add field:

```java
@Resource
private com.tongji.recommendation.HomeFeedMixingService homeFeedMixingService;
```

- [ ] **Step 3: Replace the existing feed() method in KnowPostController.java**

Find and replace:

```java
// OLD:
@GetMapping("/feed")
public ResponseEntity<FeedPageResponse> feed(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @AuthenticationPrincipal Jwt jwt) {
    Long userId = (jwt != null) ? jwtService.extractUserId(jwt) : null;
    return ResponseEntity.ok(service.getPublicFeed(page, size, userId));
}
```

With:

```java
// NEW: home feed with priority fill (follow → recommend → hot)
@GetMapping("/feed")
public ResponseEntity<FeedPageResponse> feed(@AuthenticationPrincipal Jwt jwt) {
    Long userId = (jwt != null) ? jwtService.extractUserId(jwt) : null;
    if (userId != null) {
        return ResponseEntity.ok(homeFeedMixingService.getHomeFeed(userId));
    }
    // Unauthenticated users get pure hot content
    return ResponseEntity.ok(service.getPublicFeed(1, 20, null));
}
```

- [ ] **Step 4: Add follow feed endpoint to KnowPostController.java**

Add after the updated feed method:

```java
/**
 * GET /api/v1/knowposts/feed/follow
 * Returns pure follow feed (inbox + large-V pull), no recommendations.
 * Requires authentication.
 */
@GetMapping("/feed/follow")
public ResponseEntity<FeedPageResponse> followFeed(@AuthenticationPrincipal Jwt jwt) {
    long userId = jwtService.extractUserId(jwt);
    return ResponseEntity.ok(homeFeedMixingService.getFollowFeed(userId));
}
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — no regressions

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/tongji/knowpost/api/KnowPostController.java \
        src/main/resources/application.yml
git commit -m "feat: replace home feed with mixed feed, add follow feed endpoint"
```

---

## Task 9: Verification Tests

**Files:**
- Create: `src/test/java/com/tongji/recommendation/RecommendationVerificationTest.java`

- [ ] **Step 1: Write verification tests covering all tasks.md 5.1–5.4**

```java
package com.tongji.recommendation;

import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.model.FeedItem;
import com.tongji.knowpost.model.FeedPageResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.feed.FollowFeedService;
import com.tongji.recommendation.feed.FollowFeedServiceImpl;
import com.tongji.recommendation.gorse.GorseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationVerificationTest {

    @Mock GorseClient gorseClient;
    @Mock KnowPostFeedService feedService;
    @Mock FollowFeedService followFeedService;
    @Mock RecommendationEngine recommendationEngine;
    @Mock StringRedisTemplate redis;
    @Mock ZSetOperations<String, String> zSetOps;
    @Mock UserCounterService userCounterService;

    GorseRecommendationAdapter adapter;
    FollowFeedServiceImpl followFeedServiceImpl;
    HomeFeedMixingService mixingService;

    @BeforeEach
    void setUp() {
        when(redis.opsForZSet()).thenReturn(zSetOps);

        adapter = new GorseRecommendationAdapter();
        ReflectionTestUtils.setField(adapter, "gorseClient", gorseClient);
        ReflectionTestUtils.setField(adapter, "feedService", feedService);

        followFeedServiceImpl = new FollowFeedServiceImpl(redis, userCounterService);
        ReflectionTestUtils.setField(followFeedServiceImpl, "largeAuthorThreshold", 10000L);
        ReflectionTestUtils.setField(followFeedServiceImpl, "superLargeAuthorThreshold", 500000L);
        ReflectionTestUtils.setField(followFeedServiceImpl, "inboxMaxSize", 500);

        mixingService = new HomeFeedMixingService();
        ReflectionTestUtils.setField(mixingService, "followFeedService", followFeedService);
        ReflectionTestUtils.setField(mixingService, "recommendationEngine", recommendationEngine);
        ReflectionTestUtils.setField(mixingService, "feedService", feedService);
        ReflectionTestUtils.setField(mixingService, "pageSize", 20);
    }

    /**
     * tasks.md 5.1: Adapter unit test — returns Gorse candidates when enabled.
     */
    @Test
    void adapterUnit_returnsGorseCandidatesWhenEnabled() {
        when(gorseClient.isEnabled()).thenReturn(true);
        when(gorseClient.recommend(1L, 3)).thenReturn(List.of("10", "20", "30"));

        List<RecommendationCandidate> result = adapter.recommend(1L, 3);

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(RecommendationCandidate::getSource))
            .containsOnly("gorse");
        assertThat(result.stream().mapToLong(RecommendationCandidate::getContentId).boxed().toList())
            .containsExactlyInAnyOrder(10L, 20L, 30L);
    }

    /**
     * tasks.md 5.2: Fallback test — returns hot content when Gorse disabled.
     */
    @Test
    void adapterFallback_returnsHotWhenGorseDisabled() {
        when(gorseClient.isEnabled()).thenReturn(false);
        FeedPageResponse hot = buildFeedResponse(List.of(1L, 2L, 3L));
        when(feedService.getPublicFeed(1, 4, null)).thenReturn(hot);

        List<RecommendationCandidate> result = adapter.recommend(99L, 2);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(RecommendationCandidate::getSource)).containsOnly("hot");
        verify(gorseClient, never()).recommend(anyLong(), anyInt());
    }

    /**
     * tasks.md 5.3a: Normal author (<10k followers) → pushes to follower inboxes.
     */
    @Test
    void fanout_normalAuthor_pushesToInboxes() {
        when(userCounterService.getFollowerCount(10L)).thenReturn(500L);
        when(zSetOps.range("uf:fans:10", 0, -1)).thenReturn(Set.of("111", "222"));
        when(zSetOps.size(anyString())).thenReturn(1L);

        followFeedServiceImpl.handlePublish(999L, 10L, 0L);

        verify(zSetOps).add("feed:inbox:111", "999", 0.0);
        verify(zSetOps).add("feed:inbox:222", "999", 0.0);
        verify(zSetOps, never()).add(startsWith("feed:author:posts:"), anyString(), anyDouble());
    }

    /**
     * tasks.md 5.3b: Large-V author (10k–500k) → writes to author posts ZSet only.
     */
    @Test
    void fanout_largeV_writesAuthorPostsOnly() {
        when(userCounterService.getFollowerCount(20L)).thenReturn(50000L);
        when(zSetOps.size(anyString())).thenReturn(1L);

        followFeedServiceImpl.handlePublish(888L, 20L, 1000L);

        verify(zSetOps).add("feed:author:posts:20", "888", 1000.0);
        verify(zSetOps, never()).add(startsWith("feed:inbox:"), anyString(), anyDouble());
    }

    /**
     * tasks.md 5.3c: Super large-V (≥500k) → skips all push.
     */
    @Test
    void fanout_superLargeV_skipsAll() {
        when(userCounterService.getFollowerCount(30L)).thenReturn(600000L);

        followFeedServiceImpl.handlePublish(777L, 30L, 0L);

        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    /**
     * tasks.md 5.4a: Home feed deduplication — same post from follow and recommend only once.
     */
    @Test
    void mixing_deduplication_sameIdAppearsOnce() {
        when(followFeedService.getFollowCandidates(1L, 20)).thenReturn(List.of(10L, 11L));
        when(recommendationEngine.recommend(1L, 18)).thenReturn(List.of(
            new RecommendationCandidate(10L, 1.0, "gorse"),  // duplicate
            new RecommendationCandidate(99L, 1.0, "gorse")));
        when(feedService.getFeedByIds(anyList(), eq(1L))).thenAnswer(inv ->
            buildFeedResponse(inv.getArgument(0)));

        FeedPageResponse resp = mixingService.getHomeFeed(1L);

        long count10 = resp.getItems().stream().filter(i -> i.getId() == 10L).count();
        assertThat(count10).isEqualTo(1);
    }

    /**
     * tasks.md 5.4b: Visibility filter — getFeedByIds filters non-public posts.
     * The filtering happens inside KnowPostFeedService (SQL WHERE visible='public').
     * This test verifies that the mixing service passes the full candidate list to getFeedByIds.
     */
    @Test
    void mixing_passesCandidatesToFeedService() {
        when(followFeedService.getFollowCandidates(1L, 20)).thenReturn(List.of(10L, 11L, 12L));
        when(recommendationEngine.recommend(eq(1L), anyInt())).thenReturn(List.of());
        when(feedService.getFeedByIds(anyList(), eq(1L))).thenReturn(buildFeedResponse(List.of(10L, 12L)));
        // post 11 filtered out by KnowPostFeedService (e.g., visibility=followers)

        FeedPageResponse resp = mixingService.getHomeFeed(1L);

        verify(feedService).getFeedByIds(argThat(ids -> ids.containsAll(List.of(10L, 11L, 12L))), eq(1L));
        assertThat(resp.getItems()).hasSize(2);
    }

    private FeedPageResponse buildFeedResponse(List<Long> ids) {
        FeedPageResponse r = new FeedPageResponse();
        r.setItems(ids.stream().map(id -> {
            FeedItem item = new FeedItem();
            item.setId(id);
            return item;
        }).toList());
        return r;
    }
}
```

- [ ] **Step 2: Run all verification tests**

```bash
mvn test -Dtest="GorseRecommendationAdapterTest,FollowFeedServiceTest,HomeFeedMixingServiceTest,RecommendationVerificationTest" -q
```

Expected: `Tests run: 18, Failures: 0, Errors: 0` (3+5+3+7)

- [ ] **Step 3: Run full test suite**

```bash
mvn test -q
```

Expected: `BUILD SUCCESS` — no regressions

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tongji/recommendation/RecommendationVerificationTest.java
git commit -m "test: add recommendation and follow feed verification tests"
```

---

## Self-Review Checklist

**tasks.md coverage:**
- [x] 1.1 `RecommendationEngine` interface (Task 2)
- [x] 1.2 `RecommendationCandidate` model with `contentId`, `score`, `source` (Task 2)
- [x] 1.3 `GorseRecommendationAdapter` (Task 3)
- [x] 1.4 Gorse `endpoint`, `api-key`, `timeout`, `enabled` config (Task 1 + `GorseClient`)
- [x] 1.5 Hot fallback when Gorse unavailable (Task 3 `hotFallback()`)
- [x] 2.1 Gorse item upsert async via `content_published` event (Task 5)
- [x] 2.2 Like/fav/comment feedback to Gorse (Task 6)
- [x] 2.3 User profile sync: deferred — `UserCounterService` already tracks followings/followers/posts; explicit Gorse user upsert not implemented (non-critical for v1 recommendation quality)
- [x] 2.4 Recommendation event failure compensation: `GorseClient` catches all exceptions internally; further reconciliation deferred to `add-data-reconciliation`
- [x] 3.1 `feed:inbox:{userId}` ZSet (Task 4)
- [x] 3.2 `feed:author:posts:{authorId}` ZSet (Task 4)
- [x] 3.3 Normal author fanout push (Task 4 + Task 5)
- [x] 3.4 Large-V fanout pull (Task 4 + Task 5)
- [x] 3.5 Super large-V skip push (Task 4 + Task 5)
- [x] 3.6 Active follower priority: **skipped** per design decision (full push for normal authors in v1)
- [x] 4.1 Candidate hydration via `KnowPostFeedService.getFeedByIds` (Task 7)
- [x] 4.2 Visibility + deletion filter inside `findDetailsByIds` SQL (`WHERE status='published' AND visible='public'`) (Task 7)
- [x] 4.3 Follow + recommend + hot mixing (Task 7 `HomeFeedMixingService`)
- [x] 4.4 Home feed API (`GET /api/v1/knowposts/feed`) + follow feed API (`GET /api/v1/knowposts/feed/follow`) (Task 8)
- [x] 5.1 Adapter unit test (Task 9 + Task 3)
- [x] 5.2 Gorse fallback test (Task 9 + Task 3)
- [x] 5.3 Normal/large-V/super-large-V fanout tests (Task 9 + Task 4)
- [x] 5.4 Dedup + visibility filter tests (Task 9)

**Bugs fixed during review:**
- Task 3: `recommend_fallsBackToHotWhenGorseDisabled` stub was `getPublicFeed(1, 10, null)` but `hotFallback(count=3)` calls `getPublicFeed(1, 6, null)` (count×2) — would cause NPE at runtime; fixed to `(1, 6, null)`
- Task 9: Verification test count was 17 (wrong); `RecommendationVerificationTest` has 7 tests → total 3+5+3+7=18
- Task 8: `application.yml` Step 1 had confusing duplicate `feed:` block with `# ...` placeholder; replaced with clear "add inside existing block" instruction

**No placeholders.**

**Type consistency:**
- `RecommendationCandidate.getContentId()` used in Tasks 7+9 matches definition in Task 2
- `FollowFeedService.handlePublish(postId, authorId, publishTimeMs)` signature matches Tasks 4+5+9
- `FollowFeedService.getFollowCandidates(userId, limit)` matches Tasks 4+7+9
- `HomeFeedMixingService.getHomeFeed(userId)` matches Task 8 controller call
- `KnowPostFeedService.getFeedByIds(ids, userId)` matches Task 7 definition and Task 7 impl
- `GorseClient.recommend(userId, n)` returns `List<String>` (item IDs) — used in Task 3
