package com.tongji.promotion.service;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.promotion.mapper.PromotionCampaignMapper;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.model.PromotionCampaignStatus;
import com.tongji.promotion.model.PromotionResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionCampaignServiceTest {

    @Mock
    private PromotionCampaignMapper campaignMapper;

    @Mock
    private KnowPostMapper knowPostMapper;

    @Mock
    private IdService idService;

    private PromotionCampaignService service;

    @BeforeEach
    void setUp() {
        service = new PromotionCampaignService(campaignMapper, knowPostMapper, idService);
    }

    @Test
    void createCampaignInsertsOwnedPublishedPublicPost() {
        Instant start = Instant.parse("2026-06-20T10:00:00Z");
        Instant end = Instant.parse("2026-06-20T11:00:00Z");
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "public"));
        when(idService.nextId(IdNamespace.ADMIN_OPERATION)).thenReturn(201L);

        PromotionCampaign campaign = service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT, start, end);

        assertThat(campaign.getId()).isEqualTo(201L);
        assertThat(campaign.getPostId()).isEqualTo(1001L);
        assertThat(campaign.getStatus()).isEqualTo(PromotionCampaignStatus.ACTIVE);
        verify(campaignMapper).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsMissingPost() {
        when(knowPostMapper.findById(1001L)).thenReturn(null);

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsPostNotOwnedByCreator() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 99L, "published", "public"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsUnpublishedPost() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "draft", "public"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsNonPublicPostForFeedTopSlot() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "followers"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsNonPublicPostForSearchTopSlot() {
        when(knowPostMapper.findById(1001L)).thenReturn(post(1001L, 42L, "published", "followers"));

        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.SEARCH_TOP_SLOT,
                Instant.parse("2026-06-20T10:00:00Z"),
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(campaignMapper, never()).insert(any(PromotionCampaign.class));
    }

    @Test
    void createCampaignRejectsNullStartAt() {
        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                null,
                Instant.parse("2026-06-20T11:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(knowPostMapper, never()).findById(anyLong());
    }

    @Test
    void createCampaignRejectsInvalidWindow() {
        assertThatThrownBy(() -> service.createCampaign(42L, 1001L, PromotionResourceType.FEED_TOP_SLOT,
                Instant.parse("2026-06-20T11:00:00Z"),
                Instant.parse("2026-06-20T10:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(knowPostMapper, never()).findById(anyLong());
    }

    private PromotionCampaign campaignWithStatus(long id, long creator, long post, PromotionResourceType type,
                                                 PromotionCampaignStatus status, String startAt, String endAt) {
        return PromotionCampaign.builder()
                .id(id)
                .creatorUserId(creator)
                .postId(post)
                .resourceType(type)
                .status(status)
                .startAt(Instant.parse(startAt))
                .endAt(Instant.parse(endAt))
                .createdAt(Instant.parse(startAt))
                .updatedAt(Instant.parse(startAt))
                .build();
    }

    private KnowPost post(long id, long creatorId, String status, String visible) {
        return KnowPost.builder()
                .id(id)
                .creatorId(creatorId)
                .status(status)
                .visible(visible)
                .build();
    }
}
