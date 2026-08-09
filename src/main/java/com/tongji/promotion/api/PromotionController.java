package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.AuthorizePromotionBidEscrowRequest;
import com.tongji.promotion.api.dto.CreatePromotionCampaignRequest;
import com.tongji.promotion.api.dto.PromotionAllocationView;
import com.tongji.promotion.api.dto.PromotionBidEscrowAuthorizationResponse;
import com.tongji.promotion.api.dto.PromotionCampaignResponse;
import com.tongji.promotion.bprime.model.PromotionAuctionSnapshot;
import com.tongji.promotion.bprime.service.PromotionBidEscrowService;
import com.tongji.promotion.bprime.service.PromotionSnapshotService;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationService;
import com.tongji.promotion.service.PromotionCampaignService;
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
 * 推广位竞价 HTTP API：创建/查询活动、授权保证金、查询当前有效位分配。
 */
@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionCampaignService campaignService;
    private final PromotionBidEscrowService bidEscrowService;
    private final PromotionSnapshotService snapshotService;
    private final PromotionAllocationService allocationService;
    private final JwtService jwtService;

    @PostMapping("/campaigns")
    public PromotionCampaignResponse createCampaign(@Valid @RequestBody CreatePromotionCampaignRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        PromotionResourceType resourceType = parseResourceType(request.resourceType());
        return PromotionCampaignResponse.from(
                campaignService.createCampaign(userId, request.postId(), resourceType,
                        request.startAt(), request.endAt()));
    }

    @GetMapping("/campaigns/{campaignId}")
    public PromotionCampaignResponse getCampaign(@PathVariable long campaignId) {
        return PromotionCampaignResponse.from(campaignService.getCampaign(campaignId));
    }

    @PostMapping("/campaigns/{campaignId}/escrow")
    public PromotionBidEscrowAuthorizationResponse authorizeBidEscrow(
            @PathVariable long campaignId,
            @Valid @RequestBody AuthorizePromotionBidEscrowRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return bidEscrowService.authorize(userId, campaignId, request.amount(), Instant.now());
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

    private PromotionResourceType parseResourceType(String raw) {
        try {
            return PromotionResourceType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_INVALID_RESOURCE);
        }
    }
}
