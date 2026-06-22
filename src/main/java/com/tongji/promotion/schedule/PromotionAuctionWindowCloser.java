package com.tongji.promotion.schedule;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.kafka.PromotionDecisionLogPort;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionResourceType;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.promotion.service.PromotionAuctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PromotionAuctionWindowCloser {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionService auctionService;
    private final PromotionDecisionLogPort decisionLogPort;
    private final PromotionAllocationCacheService cacheService;
    private final IdService idService;
    private final PromotionBPrimeProperties bprimeProperties;

    public void closeDueWindows(Instant now, int batchSize) {
        for (PromotionAuctionWindow window : windowMapper.listClosableWindows(now, batchSize)) {
            if (!bprimeProperties.isEnabled()) {
                Instant allocationStartAt = window.getWindowEndAt();
                long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
                Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
                auctionService.settleWindow(window,
                        bidMapper.listActiveBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt),
                        now);
                cacheService.refreshActiveAllocations(window.getResourceType(), now);
                continue;
            }
            decisionLogPort.append(new PromotionAuctionDecision(
                    "promotion-bprime-close-" + idService.nextId(IdNamespace.ADMIN_OPERATION),
                    "promotion-bprime-close-window-" + window.getId(),
                    "window-close:" + window.getId() + ":" + window.getWindowEndAt(),
                    window.getId(),
                    0L,
                    0L,
                    0L,
                    window.getResourceType().name(),
                    "WINDOW_CLOSED",
                    false,
                    null,
                    0L,
                    List.of(),
                    List.of(),
                    now));
            cacheService.refreshActiveAllocations(window.getResourceType(), now);
        }
    }

    public void refreshCurrentAllocations(Instant now) {
        cacheService.refreshActiveAllocations(PromotionResourceType.FEED_TOP_SLOT, now);
        cacheService.refreshActiveAllocations(PromotionResourceType.SEARCH_TOP_SLOT, now);
    }
}
