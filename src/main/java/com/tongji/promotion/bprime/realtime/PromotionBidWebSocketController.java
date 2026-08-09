package com.tongji.promotion.bprime.realtime;

import com.tongji.promotion.api.dto.PromotionWebSocketBidAck;
import com.tongji.promotion.api.dto.PromotionWebSocketBidRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.concurrent.CompletableFuture;

/**
 * 复用推广 STOMP 连接接收出价，并把同步快拒或 RocketMQ 受理结果返回当前会话。
 *
 * <p>该协议层不实现竞价规则；HTTP 与 WebSocket 均委托同一个收单服务。</p>
 *
 * @since 2026-08-09
 */
@Controller
@ConditionalOnProperty(name = "promotion.bprime.enabled", havingValue = "true")
public class PromotionBidWebSocketController {

    private final PromotionBidWebSocketProtocolService protocolService;

    public PromotionBidWebSocketController(PromotionBidWebSocketProtocolService protocolService) {
        this.protocolService = protocolService;
    }

    /**
     * 提交推广出价；确定必败时同步返回，其他请求在 RocketMQ broker ACK 后返回 pending。
     *
     * @param request 已通过格式校验的 WebSocket 请求
     * @param principal 握手阶段从 JWT 建立的用户身份
     * @return 当前会话的私有 ACK
     */
    @MessageMapping(PromotionAuctionRealtimeChannels.BID_APPLICATION_DESTINATION)
    @SendToUser(destinations = PromotionAuctionRealtimeChannels.PRIVATE_BID_ACK_QUEUE, broadcast = false)
    public CompletableFuture<PromotionWebSocketBidAck> submit(
            @Valid PromotionWebSocketBidRequest request,
            Principal principal) {
        return protocolService.submit(request, principal);
    }
}
