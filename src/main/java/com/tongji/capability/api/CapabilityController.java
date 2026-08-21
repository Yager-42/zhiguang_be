package com.tongji.capability.api;

import com.tongji.capability.api.dto.ApplicationCapabilitiesResponse;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.wallet.config.ContentRewardProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 返回当前部署实际启用的前端业务能力。
 */
@RestController
@RequestMapping("/api/v1/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private static final String NATIVE_WEBSOCKET = "native-websocket";

    private final PromotionBPrimeProperties promotionProperties;
    private final ContentRewardProperties contentRewardProperties;

    /**
     * 查询当前运行时能力。静态路径和数据结构由控制器及 DTO 定义，本接口仅描述部署开关。
     *
     * @return 当前运行时能力
     */
    @GetMapping
    public ApplicationCapabilitiesResponse getCapabilities() {
        boolean promotionEnabled = promotionProperties.isEnabled();
        return new ApplicationCapabilitiesResponse(
                new ApplicationCapabilitiesResponse.PromotionAuctionCapability(
                        promotionEnabled,
                        promotionEnabled ? NATIVE_WEBSOCKET : null
                ),
                new ApplicationCapabilitiesResponse.ContentRewardCapability(
                        contentRewardProperties.isEnabled()
                )
        );
    }
}
