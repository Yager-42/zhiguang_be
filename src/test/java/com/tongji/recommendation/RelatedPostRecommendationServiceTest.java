package com.tongji.recommendation;

import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelatedPostRecommendationServiceTest {

    @Mock
    private GorseClient gorseClient;
    @Mock
    private KnowPostMapper knowPostMapper;
    @Mock
    private KnowPostFeedService feedService;

    private GorseProperties properties;
    private RelatedPostRecommendationService service;

    @BeforeEach
    void setUp() {
        properties = new GorseProperties();
        properties.setEnabled(true);
        service = new RelatedPostRecommendationService(gorseClient, properties, knowPostMapper, feedService);
    }

    @Test
    void relatedKeepsGorseOrderAndFillsWithHotPosts() {
        when(gorseClient.related(10L, 9)).thenReturn(List.of("11", "bad-id", "10", "12"));
        when(knowPostMapper.listFeedPublicIds(10, 0)).thenReturn(List.of(10L, 13L, 14L));
        when(feedService.getFeedByIds(
                List.of(11L, 12L, 13L, 14L),
                7L,
                KnowPostFeedService.FeedVisibilityScope.PUBLIC
        )).thenReturn(List.of(item("11"), item("12"), item("13"), item("14")));

        FeedPageResponse response = service.getRelated(10L, 3, 7L);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("11", "12", "13");
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    void relatedFallsBackToHotPostsWhenGorseIsUnavailable() {
        when(gorseClient.related(10L, 12)).thenThrow(new RestClientException("gorse down"));
        when(knowPostMapper.listFeedPublicIds(13, 0)).thenReturn(List.of(10L, 21L, 22L));
        when(feedService.getFeedByIds(
                List.of(21L, 22L),
                null,
                KnowPostFeedService.FeedVisibilityScope.PUBLIC
        )).thenReturn(List.of(item("21"), item("22")));

        FeedPageResponse response = service.getRelated(10L, 4, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("21", "22");
    }

    @Test
    void disabledGorseUsesHotPostsDirectly() {
        properties.setEnabled(false);
        when(knowPostMapper.listFeedPublicIds(7, 0)).thenReturn(List.of(31L));
        when(feedService.getFeedByIds(
                List.of(31L),
                null,
                KnowPostFeedService.FeedVisibilityScope.PUBLIC
        )).thenReturn(List.of(item("31")));

        FeedPageResponse response = service.getRelated(10L, 2, null);

        assertThat(response.items()).extracting(FeedItemResponse::id).containsExactly("31");
        verify(gorseClient, never()).related(10L, 6);
    }

    private FeedItemResponse item(String id) {
        return FeedItemResponse.organic(
                id, "标题", "简介", null, List.of(), null, "作者", null, 0L, 0L, false, false, false
        );
    }
}
