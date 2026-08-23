package com.tongji.recommendation;

import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 提供公开知文热度榜，并将 Gorse 候选转换为当前用户可见的知文响应。
 *
 * <p>Gorse 关闭、超时或返回空榜时回退到公开知文顺序；本服务不改变 Gorse 的榜单顺序。</p>
 *
 * @since 2026-08-21
 */
@Service
public class TrendingPostRecommendationService {

    private static final int MAX_PAGE_SIZE = 50;

    private final GorseClient gorseClient;
    private final GorseProperties properties;
    private final KnowPostMapper knowPostMapper;
    private final KnowPostFeedService knowPostFeedService;

    /**
     * 创建热榜读取服务。
     *
     * @param gorseClient Gorse HTTP 客户端
     * @param properties Gorse 配置
     * @param knowPostMapper 知文查询入口
     * @param knowPostFeedService 知文 Feed 聚合服务
     */
    public TrendingPostRecommendationService(GorseClient gorseClient,
                                             GorseProperties properties,
                                             KnowPostMapper knowPostMapper,
                                             KnowPostFeedService knowPostFeedService) {
        this.gorseClient = gorseClient;
        this.properties = properties;
        this.knowPostMapper = knowPostMapper;
        this.knowPostFeedService = knowPostFeedService;
    }

    /**
     * 按 Gorse 非个性化热度顺序读取公开知文。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，范围为 1 到 50
     * @param currentUserIdNullable 当前用户 ID；匿名访问时为 {@code null}
     * @return 热门知文页；没有更多候选时 {@code hasMore=false}
     */
    public FeedPageResponse getTrending(int page, int size, Long currentUserIdNullable) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int offset = Math.multiplyExact(safePage - 1, safeSize);
        List<Long> candidateIds = loadCandidateIds(offset, safeSize + 1);
        boolean hasMore = candidateIds.size() > safeSize;
        List<Long> pageIds = candidateIds.size() > safeSize
                ? candidateIds.subList(0, safeSize)
                : candidateIds;
        List<FeedItemResponse> items = knowPostFeedService.getFeedByIds(
                pageIds,
                currentUserIdNullable,
                KnowPostFeedService.FeedVisibilityScope.PUBLIC
        );
        return new FeedPageResponse(items, safePage, safeSize, hasMore);
    }

    private List<Long> loadCandidateIds(int offset, int count) {
        if (properties.isEnabled()) {
            try {
                List<Long> ids = parseIds(gorseClient.trending(offset, count));
                if (!ids.isEmpty()) {
                    return ids;
                }
            } catch (RestClientException ignored) {
                // 热榜是可降级读路径；Gorse 不可用时使用公开知文顺序。
            }
        }
        List<Long> fallback = knowPostMapper.listFeedPublicIds(count, offset);
        return fallback == null ? List.of() : dedupe(fallback);
    }

    private List<Long> parseIds(List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        List<Long> parsed = new ArrayList<>(rawIds.size());
        for (String rawId : rawIds) {
            try {
                parsed.add(Long.parseLong(rawId));
            } catch (NumberFormatException ignored) {
                // 单个脏候选不应使整页热榜失败。
            }
        }
        return dedupe(parsed);
    }

    private List<Long> dedupe(List<Long> ids) {
        Set<Long> seen = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null) {
                seen.add(id);
            }
        }
        return new ArrayList<>(seen);
    }
}
