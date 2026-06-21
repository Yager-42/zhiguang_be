package com.tongji.promotion.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.promotion.api.dto.CreatePaidBoostCampaignRequest;
import com.tongji.promotion.api.dto.PaidBoostCampaignResponse;
import com.tongji.promotion.model.PaidBoostChannel;
import com.tongji.promotion.service.PaidBoostCampaignService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 付费加权（非拍卖）API：单接口创建 boost 投放活动，与 slot auction 控制器分域。
 */
@RestController
@RequestMapping("/api/v1/promotions")
public class PaidBoostController {

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

    private PaidBoostChannel parseChannel(String raw) {
        PaidBoostChannel channel = PaidBoostChannel.fromString(raw);
        if (channel == null) {
            throw new BusinessException(ErrorCode.PAID_BOOST_INVALID_CHANNEL);
        }
        return channel;
    }
}
