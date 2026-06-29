package com.tongji.knowpost.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tongji.counter.event.CounterEvent;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedCacheInvalidationListenerTest {

    private Cache<String, FeedPageResponse> feedPublicCache;
    private StringRedisTemplate redisTemplate;
    private SetOperations<String, String> setOperations;
    private UserCounterService userCounterService;
    private KnowPostMapper knowPostMapper;
    private FeedCacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        feedPublicCache = Caffeine.newBuilder().build();
        redisTemplate = mock(StringRedisTemplate.class);
        setOperations = mock(SetOperations.class);
        userCounterService = mock(UserCounterService.class);
        knowPostMapper = mock(KnowPostMapper.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        listener = new FeedCacheInvalidationListener(
                feedPublicCache,
                redisTemplate,
                userCounterService,
                knowPostMapper
        );
    }

    @Test
    void counterChangeUpdatesLocalPageWithoutRequiringRedisPageJson() {
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String pageKey = "feed:public:20:1:v1";
        when(setOperations.members("feed:public:index:101:" + hourSlot)).thenReturn(Set.of(pageKey));
        when(setOperations.members("feed:public:index:101:" + (hourSlot - 1))).thenReturn(Set.of());
        KnowPost post = new KnowPost();
        post.setCreatorId(9L);
        when(knowPostMapper.findById(101L)).thenReturn(post);
        feedPublicCache.put(pageKey, new FeedPageResponse(
                List.of(item("101", 4L, 2L, true, false)),
                1,
                20,
                false,
                null
        ));

        listener.onCounterChanged(new CounterEvent("event-1", 1L, "knowpost", "101", "like", 0, 7L, 1));

        FeedItemResponse updated = feedPublicCache.getIfPresent(pageKey).items().get(0);
        assertThat(updated.likeCount()).isEqualTo(5L);
        assertThat(updated.favoriteCount()).isEqualTo(2L);
        assertThat(updated.liked()).isTrue();
        assertThat(updated.faved()).isFalse();
        verify(userCounterService).incrementLikesReceived(9L, 1);
        verify(setOperations, never()).remove("feed:public:index:101:" + hourSlot, pageKey);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void favoriteDeltaDoesNotDropBelowZero() {
        long hourSlot = System.currentTimeMillis() / 3600000L;
        String pageKey = "feed:public:20:1:v1";
        when(setOperations.members("feed:public:index:101:" + hourSlot)).thenReturn(Set.of(pageKey));
        when(setOperations.members("feed:public:index:101:" + (hourSlot - 1))).thenReturn(Set.of());
        feedPublicCache.put(pageKey, new FeedPageResponse(
                List.of(item("101", 4L, 0L, false, true)),
                1,
                20,
                false,
                null
        ));

        listener.onCounterChanged(new CounterEvent("event-1", 1L, "knowpost", "101", "fav", 0, 7L, -1));

        FeedItemResponse updated = feedPublicCache.getIfPresent(pageKey).items().get(0);
        assertThat(updated.likeCount()).isEqualTo(4L);
        assertThat(updated.favoriteCount()).isZero();
        assertThat(updated.liked()).isFalse();
        assertThat(updated.faved()).isTrue();
    }

    private FeedItemResponse item(String id, Long likeCount, Long favoriteCount, Boolean liked, Boolean faved) {
        return FeedItemResponse.organic(
                id,
                "title",
                "description",
                null,
                List.of(),
                null,
                "author",
                null,
                likeCount,
                favoriteCount,
                liked,
                faved,
                false
        );
    }
}
