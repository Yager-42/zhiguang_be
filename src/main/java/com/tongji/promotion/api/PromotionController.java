package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.AuthorizePromotionBidEscrowRequest;
import com.tongji.promotion.api.dto.EnterCurrentPromotionAuctionRequest;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.api.dto.PromotionAuctionEntryResponse;
import com.tongji.promotion.api.dto.PromotionBidEscrowAuthorizationResponse;
import com.tongji.promotion.api.dto.PromotionCampaignResponse;
import com.tongji.promotion.api.dto.PromotionCampaignListResponse;
import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.bprime.service.PromotionBidEscrowService;
import com.tongji.promotion.bprime.service.PromotionSnapshotService;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionCampaign;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.promotion.service.PromotionCampaignService;
import com.tongji.promotion.service.PromotionAuctionWindowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 推广位竞价 HTTP API：报名系统场次、首次出价前授权保证金、查询当前有效位分配。
 */
@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionCampaignService campaignService;
    private final PromotionBidEscrowService bidEscrowService;
    private final PromotionSnapshotService snapshotService;
    private final PromotionAllocationService allocationService;
    private final PromotionAuctionWindowService windowService;
    private final JwtService jwtService;

    @GetMapping("/campaigns/{campaignId}")
    public PromotionCampaignResponse getCampaign(@PathVariable long campaignId) {
        return PromotionCampaignResponse.from(campaignService.getCampaignDetails(campaignId));
    }

    @GetMapping("/campaigns/mine")
    public PromotionCampaignListResponse listMyCampaigns(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        long userId = jwtService.extractUserId(jwt);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);
        List<PromotionCampaignResponse> campaigns = campaignService
                .listCampaigns(userId, safeLimit + 1, safeOffset).stream()
                .map(PromotionCampaignResponse::from)
                .toList();
        boolean hasMore = campaigns.size() > safeLimit;
        return new PromotionCampaignListResponse(
                campaigns.stream().limit(safeLimit).toList(), safeLimit, safeOffset, hasMore);
    }

    @PostMapping("/campaigns/{campaignId}/escrow")
    public PromotionBidEscrowAuthorizationResponse authorizeBidEscrow(
            @PathVariable long campaignId,
            @Valid @RequestBody AuthorizePromotionBidEscrowRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return bidEscrowService.authorize(userId, campaignId, request.amount(), Instant.now());
    }

    @PostMapping("/windows/current/entries")
    public PromotionAuctionEntryResponse enterCurrentAuction(
            @Valid @RequestBody EnterCurrentPromotionAuctionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        PromotionResourceType resourceType = parseResourceType(request.resourceType());
        PromotionAuctionWindow window = windowService.getCurrentOpenWindow(resourceType);
        PromotionCampaign participation = campaignService.getOrCreateParticipation(
                userId, request.postId(), resourceType, window);
        return new PromotionAuctionEntryResponse(
                String.valueOf(window.getId()),
                PromotionCampaignResponse.from(campaignService.describe(participation)));
    }

    @GetMapping("/allocations/active")
    public List<PromotionAllocationView> getActiveAllocations(@RequestParam String resourceType) {
        PromotionResourceType type = parseResourceType(resourceType);
        return allocationService.getActive(type);
    }

    @GetMapping("/windows/{auctionWindowId}/snapshot")
    public PromotionAuctionSnapshot snapshot(@PathVariable long auctionWindowId) {
        return snapshotService.snapshot(auctionWindowId);
    }

    @GetMapping("/windows/current/snapshot")
    public PromotionAuctionSnapshot currentSnapshot(@RequestParam String resourceType) {
        PromotionResourceType type = parseResourceType(resourceType);
        return snapshotService.snapshot(windowService.getCurrentOpenWindow(type).getId());
    }

    private PromotionResourceType parseResourceType(String raw) {
        try {
            return PromotionResourceType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_INVALID_RESOURCE);
        }
    }
}
