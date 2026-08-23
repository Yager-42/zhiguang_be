package com.tongji.recommendation;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrendingPostRecommendationServiceTest {

    @Test
    void keepsGorseOrderAndUsesLookaheadForHasMore() {
        GorseClient gorseClient = mock(GorseClient.class);
        GorseProperties properties = new GorseProperties();
        properties.setEnabled(true);
        KnowPostMapper knowPostMapper = mock(KnowPostMapper.class);
        KnowPostFeedService feedService = mock(KnowPostFeedService.class);
        TrendingPostRecommendationService service = new TrendingPostRecommendationService(
                gorseClient, properties, knowPostMapper, feedService);
        when(gorseClient.trending(0, 3)).thenReturn(List.of("9", "invalid", "8", "7"));
        when(feedService.getFeedByIds(
                List.of(9L, 8L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC)).thenReturn(List.of());

        var result = service.getTrending(1, 2, 42L);

        assertThat(result.hasMore()).isTrue();
        verify(feedService).getFeedByIds(
                List.of(9L, 8L), 42L, KnowPostFeedService.FeedVisibilityScope.PUBLIC);
    }
}
