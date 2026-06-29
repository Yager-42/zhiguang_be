package com.tongji.knowpost.listener;

import com.github.benmanes.caffeine.cache.Cache;
import com.tongji.counter.event.CounterEvent;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.model.KnowPost;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains in-process public feed page snapshots after counter changes.
 *
 * <p>The Redis public feed read path stores page id lists and item fragments, not
 * full page JSON snapshots. The reverse index is still useful for locating local
 * Caffeine pages that may contain a changed post.</p>
 */
@Component
public class FeedCacheInvalidationListener {

    private final Cache<String, FeedPageResponse> feedPublicCache;
    private final StringRedisTemplate redis;
    private final com.tongji.counter.service.UserCounterService userCounterService;
    private final com.tongji.knowpost.mapper.KnowPostMapper knowPostMapper;

    public FeedCacheInvalidationListener(@Qualifier("feedPublicCache") Cache<String, FeedPageResponse> feedPublicCache,
                                         StringRedisTemplate redis,
                                         com.tongji.counter.service.UserCounterService userCounterService,
                                         com.tongji.knowpost.mapper.KnowPostMapper knowPostMapper) {
        this.feedPublicCache = feedPublicCache;
        this.redis = redis;
        this.userCounterService = userCounterService;
        this.knowPostMapper = knowPostMapper;
    }

    /**
     * Applies like/favorite deltas to local public feed page snapshots and updates
     * the author's received counters when the post owner can be resolved.
     */
    @EventListener
    public void onCounterChanged(CounterEvent event) {
        if (!"knowpost".equals(event.getEntityType())) {
            return;
        }

        String metric = event.getMetric();
        if ("like".equals(metric) || "fav".equals(metric)) {
            String eid = event.getEntityId();
            int delta = event.getDelta();

            try {
                KnowPost post = knowPostMapper.findById(Long.valueOf(eid));
                if (post != null && post.getCreatorId() != null) {
                    long owner = post.getCreatorId();
                    if ("like".equals(metric)) {
                        userCounterService.incrementLikesReceived(owner, delta);
                    }
                    if ("fav".equals(metric)) {
                        userCounterService.incrementFavsReceived(owner, delta);
                    }
                }
            } catch (Exception ignored) {
            }

            long hourSlot = System.currentTimeMillis() / 3600000L;
            Set<String> keys = new LinkedHashSet<>();
            Set<String> cur = redis.opsForSet().members("feed:public:index:" + eid + ":" + hourSlot);
            if (cur != null) {
                keys.addAll(cur);
            }

            Set<String> prev = redis.opsForSet().members("feed:public:index:" + eid + ":" + (hourSlot - 1));
            if (prev != null) {
                keys.addAll(prev);
            }
            if (keys.isEmpty()) {
                return;
            }

            for (String key : keys) {
                FeedPageResponse local = feedPublicCache.getIfPresent(key);
                if (local != null) {
                    FeedPageResponse updatedLocal = adjustPageCounts(local, eid, metric, delta);
                    feedPublicCache.put(key, updatedLocal);
                }
            }
        }
    }

    /**
     * Updates the target item count in a page snapshot while preserving user flags.
     */
    private FeedPageResponse adjustPageCounts(FeedPageResponse page, String eid, String metric, int delta) {
        List<FeedItemResponse> items = new ArrayList<>(page.items().size());
        for (FeedItemResponse it : page.items()) {
            if (eid.equals(it.id())) {
                Long like = it.likeCount();
                Long fav = it.favoriteCount();

                if ("like".equals(metric)) {
                    like = Math.max(0L, (like == null ? 0L : like) + delta);
                }
                if ("fav".equals(metric)) {
                    fav = Math.max(0L, (fav == null ? 0L : fav) + delta);
                }

                it = it.withInteractions(like, fav, it.liked(), it.faved());
            }
            items.add(it);
        }

        return new FeedPageResponse(items, page.page(), page.size(), page.hasMore(), page.nextCursor());
    }
}
