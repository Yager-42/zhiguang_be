package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.CreatePaidBoostCampaignRequest;
import com.tongji.promotion.api.dto.PaidBoostCampaignResponse;
import com.tongji.promotion.api.dto.PaidBoostDeliverySummaryResponse;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.service.PaidBoostCampaignService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 付费加权（非拍卖）API：单接口创建 boost 投放活动，与 slot auction 控制器分域。
 */
@RestController
@RequestMapping("/api/v1/promotions")
public class PaidBoostController {

    private static final int DEFAULT_DELIVERY_LIMIT = 50;
    private static final int MAX_DELIVERY_LIMIT = 200;

    private final PaidBoostCampaignService campaignService;
    private final JwtService jwtService;

    public PaidBoostController(PaidBoostCampaignService campaignService, JwtService jwtService) {
        this.campaignService = campaignService;
        this.jwtService = jwtService;
    }

    @PostMapping("/boost-campaigns")
    public PaidBoostCampaignResponse createCampaign(@Valid @RequestBody CreatePaidBoostCampaignRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        PaidBoostChannel channel = parseChannel(request.channel());
        return PaidBoostCampaignResponse.from(
                campaignService.createCampaign(userId, request.postId(), channel,
                        request.bidAmount(), request.unitPrice(), request.budgetTotal(),
                        request.startAt(), request.endAt()));
    }

    @GetMapping("/boost-campaigns/{campaignId}")
    public PaidBoostCampaignResponse getCampaign(@PathVariable long campaignId) {
        return PaidBoostCampaignResponse.from(campaignService.getCampaign(campaignId));
    }

    @GetMapping("/boost-campaigns/{campaignId}/deliveries")
    public List<PaidBoostDeliverySummaryResponse> getDeliveries(@PathVariable long campaignId,
                                                                @RequestParam(value = "limit", defaultValue = "50") int limit,
                                                                @RequestParam(value = "offset", defaultValue = "0") int offset) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_DELIVERY_LIMIT));
        int safeOffset = Math.max(0, offset);
        return campaignService.listDeliveries(campaignId, safeLimit, safeOffset).stream()
                .map(PaidBoostDeliverySummaryResponse::from)
                .toList();
    }

    private PaidBoostChannel parseChannel(String raw) {
        PaidBoostChannel channel = PaidBoostChannel.fromString(raw);
        if (channel == null) {
            throw new BusinessException(ErrorCode.PAID_BOOST_INVALID_CHANNEL);
        }
        return channel;
    }
}

