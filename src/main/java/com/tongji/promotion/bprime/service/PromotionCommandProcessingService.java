package com.tongji.promotion.bprime.service;

import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.mapper.PromotionAuctionCommandMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionWalletEffect;
import com.tongji.promotion.bprime.redis.PromotionRedisDecisionAdapter;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PromotionCommandProcessingService {

    private final PromotionRedisDecisionAdapter redisDecisionAdapter;
    private final PromotionDecisionLogPort decisionLogPort;
    private final PromotionAuctionCommandMapper commandMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final WalletService walletService;

    public PromotionCommandProcessingService(PromotionRedisDecisionAdapter redisDecisionAdapter,
                                             PromotionDecisionLogPort decisionLogPort,
                                             PromotionAuctionCommandMapper commandMapper,
                                             PromotionAuctionWindowMapper windowMapper,
                                             WalletService walletService) {
        this.redisDecisionAdapter = redisDecisionAdapter;
        this.decisionLogPort = decisionLogPort;
        this.commandMapper = commandMapper;
        this.windowMapper = windowMapper;
        this.walletService = walletService;
    }

    public void process(PromotionAuctionCommand command) {
        var commandRecord = commandMapper.findByCommandId(command.commandId());
        if (commandRecord != null && "LOG_FAILED".equals(commandRecord.getStatus())) {
            return;
        }
        PromotionAuctionWindow window = windowMapper.findById(command.auctionWindowId());
        long reservePrice = window == null ? 1L : window.getReservePrice();
        String windowStatus = window == null || window.getStatus() == null ? "OPEN" : window.getStatus().name();
        PromotionAuctionDecision decision = redisDecisionAdapter.decide(command, reservePrice, windowStatus, Instant.now());
        long heldAmount = 0L;
        boolean decisionLogged = false;
        try {
            if (decision.accepted()) {
                for (PromotionWalletEffect effect : decision.walletEffects()) {
                    if ("HOLD".equals(effect.effectType())) {
                        if (effect.amount() > 0) {
                            walletService.hold(effect.ownerUserId(), effect.amount(), WalletLedgerReason.PROMOTION_BPRIME_HOLD,
                                    WalletBusinessType.PROMOTION, effect.businessRef());
                            heldAmount += effect.amount();
                        }
                    }
                }
            }
            decisionLogPort.append(decision);
            decisionLogged = true;
            redisDecisionAdapter.commit(decision);
            commandMapper.updateStatus(command.commandId(), "DECIDED");
        } catch (RuntimeException e) {
            if (decisionLogged) {
                commandMapper.updateStatus(command.commandId(), "DECISION_LOGGED");
            } else {
                if (heldAmount > 0) {
                    walletService.releaseHold(command.bidderUserId(), heldAmount,
                            WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                            "promotion-bprime:" + command.commandId() + ":hold-release-after-log-fail");
                }
                commandMapper.updateStatus(command.commandId(), "LOG_FAILED");
            }
            throw e;
        }
    }
}
