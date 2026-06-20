package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.CreatePromotionCampaignRequest;
import com.tongji.promotion.api.dto.PromotionBidResponse;
import com.tongji.promotion.api.dto.PromotionCampaignResponse;
import com.tongji.promotion.api.dto.SubmitPromotionBidRequest;
import com.tongji.promotion.model.PromotionResourceType;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 推广位竞价写 API：创建活动、查询活动、提交出价。
 * active allocation 读接口由 {@code PromotionAllocationService} 在 Task 4 接入。
 */
@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionCampaignService campaignService;
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

    @PostMapping("/campaigns/{campaignId}/bids")
    public PromotionBidResponse submitBid(@PathVariable long campaignId,
                                          @Valid @RequestBody SubmitPromotionBidRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        return PromotionBidResponse.from(
                campaignService.submitBid(userId, campaignId, request.bidAmount(), Instant.now()));
    }

    private PromotionResourceType parseResourceType(String raw) {
        try {
            return PromotionResourceType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROMOTION_BID_INVALID_RESOURCE);
        }
    }
}
