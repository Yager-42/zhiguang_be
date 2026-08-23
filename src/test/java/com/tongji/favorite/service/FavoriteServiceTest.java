package com.tongji.favorite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.id.IdService;
import com.tongji.favorite.mapper.FavoriteMapper;
import com.tongji.favorite.model.UserFavorite;
import com.tongji.knowpost.api.dto.FeedItemResponse;
import com.tongji.knowpost.api.dto.FeedPageResponse;
import com.tongji.knowpost.service.KnowPostFeedService;
import com.tongji.knowpost.service.KnowPostService;
import com.tongji.outbox.OutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FavoriteServiceTest {

    @Test
    void favoriteWritesRelationAndOutboxOnlyWhenStateChanges() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.knowPostService.isPublished(101L)).thenReturn(true);
        when(fixture.mapper.insertIgnore(eq(42L), eq(101L), any(Instant.class))).thenReturn(1);
        when(fixture.idService.nextId(any())).thenReturn(9001L);

        FavoriteWriteResult result = fixture.service.favorite(42L, "knowpost", "101");

        assertThat(result).isEqualTo(new FavoriteWriteResult(true, true));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(fixture.outboxMapper).insert(
                eq(9001L),
                eq(FavoriteService.AGGREGATE_TYPE),
                eq(42L),
                eq(FavoriteService.EVENT_TYPE),
                payload.capture()
        );
        JsonNode event = fixture.objectMapper.readTree(payload.getValue());
        assertThat(event.path("eventId").asText()).isEqualTo("9001");
        assertThat(event.path("userId").asLong()).isEqualTo(42L);
        assertThat(event.path("postId").asLong()).isEqualTo(101L);
        assertThat(event.path("faved").asBoolean()).isTrue();
        assertThat(event.path("delta").asInt()).isEqualTo(1);
    }

    @Test
    void repeatedFavoriteDoesNotWriteOutbox() {
        Fixture fixture = new Fixture();
        when(fixture.knowPostService.isPublished(101L)).thenReturn(true);
        when(fixture.mapper.insertIgnore(eq(42L), eq(101L), any(Instant.class))).thenReturn(0);

        FavoriteWriteResult result = fixture.service.favorite(42L, "knowpost", "101");

        assertThat(result).isEqualTo(new FavoriteWriteResult(false, true));
        verify(fixture.outboxMapper, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void unpublishedPostIsRejectedBeforeWritingRelation() {
        Fixture fixture = new Fixture();
        when(fixture.knowPostService.isPublished(101L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.favorite(42L, "knowpost", "101"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("知文不存在或不可收藏");

        verifyNoInteractions(fixture.mapper, fixture.outboxMapper);
    }

    @Test
    void unfavoriteWritesNegativeOutboxOnlyForExistingRelation() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.mapper.delete(42L, 101L)).thenReturn(1);
        when(fixture.idService.nextId(any())).thenReturn(9002L);

        FavoriteWriteResult result = fixture.service.unfavorite(42L, "knowpost", "101");

        assertThat(result).isEqualTo(new FavoriteWriteResult(true, false));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(fixture.outboxMapper).insert(
                eq(9002L),
                eq(FavoriteService.AGGREGATE_TYPE),
                eq(42L),
                eq(FavoriteService.EVENT_TYPE),
                payload.capture()
        );
        JsonNode event = fixture.objectMapper.readTree(payload.getValue());
        assertThat(event.path("faved").asBoolean()).isFalse();
        assertThat(event.path("delta").asInt()).isEqualTo(-1);
    }

    @Test
    void repeatedUnfavoriteDoesNotWriteOutbox() {
        Fixture fixture = new Fixture();
        when(fixture.mapper.delete(42L, 101L)).thenReturn(0);

        FavoriteWriteResult result = fixture.service.unfavorite(42L, "knowpost", "101");

        assertThat(result).isEqualTo(new FavoriteWriteResult(false, false));
        verify(fixture.outboxMapper, never()).insert(any(), any(), any(), any(), any());
    }

    @Test
    void listUsesStableCursorAndPreservesFavoriteOrder() {
        Fixture fixture = new Fixture();
        UserFavorite first = favorite(42L, 103L, "2026-08-21T10:00:00Z");
        UserFavorite second = favorite(42L, 102L, "2026-08-21T09:00:00Z");
        UserFavorite lookahead = favorite(42L, 101L, "2026-08-21T08:00:00Z");
        when(fixture.mapper.listPage(42L, null, null, 3)).thenReturn(List.of(first, second, lookahead));
        List<FeedItemResponse> items = List.of(post("103"), post("102"));
        when(fixture.feedService.getFeedByIds(
                List.of(103L, 102L),
                42L,
                KnowPostFeedService.FeedVisibilityScope.FOLLOW
        )).thenReturn(items);

        FeedPageResponse result = fixture.service.list(42L, null, 2);

        assertThat(result.items()).isEqualTo(items);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isEqualTo("1787302800000:102");
    }

    @Test
    void rejectsUnsupportedEntityAndMalformedCursor() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.favorite(42L, "comment", "101"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.list(42L, "bad", 20))
                .isInstanceOf(BusinessException.class);
    }

    private static UserFavorite favorite(long userId, long postId, String createdAt) {
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setPostId(postId);
        favorite.setCreatedAt(Instant.parse(createdAt));
        return favorite;
    }

    private static FeedItemResponse post(String id) {
        return FeedItemResponse.organic(id, "title", "description", null, List.of(), null, "author", null,
                0L, 0L, false, true, null);
    }

    private static final class Fixture {
        private final FavoriteMapper mapper = mock(FavoriteMapper.class);
        private final OutboxMapper outboxMapper = mock(OutboxMapper.class);
        private final IdService idService = mock(IdService.class);
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final KnowPostFeedService feedService = mock(KnowPostFeedService.class);
        private final KnowPostService knowPostService = mock(KnowPostService.class);
        private final FavoriteService service = new FavoriteService(
                mapper,
                outboxMapper,
                idService,
                objectMapper,
                feedService,
                knowPostService
        );
    }
}
