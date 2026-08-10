package com.tongji.promotion.bprime.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 严格按 Redis Stream 版本将竞价事实投影到 MySQL。
 */
@Service
public class PromotionDecisionProjectionService {

    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final PromotionAllocationCacheService cacheService;
    private final IdService idService;
    private final WalletService walletService;
    public PromotionDecisionProjectionService(PromotionProjectionCheckpointMapper checkpointMapper,
                                              PromotionBidEscrowMapper escrowMapper,
                                              PromotionBidMapper bidMapper,
                                              PromotionAuctionWindowMapper windowMapper,
                                              PromotionSlotAllocationMapper allocationMapper,
                                              WalletService walletService,
                                              PromotionAllocationCacheService cacheService,
                                              IdService idService) {
        this.checkpointMapper = checkpointMapper;
        this.escrowMapper = escrowMapper;
        this.bidMapper = bidMapper;
        this.windowMapper = windowMapper;
        this.allocationMapper = allocationMapper;
        this.walletService = walletService;
        this.cacheService = cacheService;
        this.idService = idService;
    }

    @Transactional
    public void project(PromotionAuctionDecision decision) {
        projectBatch(List.of(new PromotionDecisionProjectionItem(
                decision, decision.decisionVersion() + "-0")));
    }

    /**
     * 同一批次内按传入顺序投影，并为每个窗口只提交一次最终 checkpoint。
     */
    @Transactional
    public List<PromotionAuctionDecision> projectBatch(List<PromotionDecisionProjectionItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<Long, ProjectionState> states = new LinkedHashMap<>();
        List<PromotionAuctionDecision> projected = new ArrayList<>(items.size());
        for (PromotionDecisionProjectionItem item : items) {
            PromotionAuctionDecision decision = item.decision();
            ProjectionState state = states.computeIfAbsent(decision.auctionWindowId(), this::loadProjectionState);
            requireMatchingStreamId(decision, item.streamId());
            if (!isDuplicate(decision, state)) {
                requireNextVersion(decision, state.lastDecisionVersion);
                applyDecision(decision);
                projected.add(decision);
                state.lastDecisionId = decision.decisionId();
                state.lastDecisionVersion = decision.decisionVersion();
            }
            state.lastStreamId = item.streamId();
        }
        states.forEach((windowId, state) -> checkpointMapper.upsert(
                windowId, state.lastDecisionId, state.lastDecisionVersion, state.lastStreamId));
        return List.copyOf(projected);
    }

    private void applyDecision(PromotionAuctionDecision decision) {
        String type = decision.decisionType();
        if ("AUCTION_EXTENDED".equals(type)) {
            // 信息事件：反狙击延长只推进 checkpoint，不落 MySQL（Go AUCTION_EXTENDED 同构）。
            return;
        }
        if ("AUCTION_SOLD".equals(type) || "AUCTION_NO_BID".equals(type)) {
            settleWindow(decision);
            return;
        }
        if (!decision.accepted() || !"BID_ACCEPTED".equals(type)) {
            throw new IllegalArgumentException("unsupported promotion Stream event: " + decision.decisionType());
        }
        if (escrowMapper.updateCurrentHold(decision.auctionWindowId(), decision.campaignId(),
                decision.bidAmount(), decision.decidedAt()) != 1) {
            throw new IllegalStateException("promotion bid escrow projection failed: auctionWindowId="
                    + decision.auctionWindowId() + ", campaignId=" + decision.campaignId());
        }
        bidMapper.upsertAccepted(PromotionBid.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .campaignId(decision.campaignId())
                .auctionWindowId(decision.auctionWindowId())
                .bidderUserId(decision.bidderUserId())
                .bidAmount(decision.bidAmount())
                .walletBusinessRef(holdBusinessRef(decision))
                .commandId(decision.commandId())
                .decisionId(decision.decisionId())
                .decisionStatus(decision.decisionType())
                .status(PromotionBidStatus.ACTIVE)
                .createdAt(decision.decidedAt())
                .updatedAt(decision.decidedAt())
                .postId(decision.postId())
                .build());
    }

    /**
     * 英式第一价格结算（Go close_auction 同构，M8）：
     * <ul>
     *   <li>{@code AUCTION_SOLD}：终态决策携带唯一赢家与 winningAmount（= 最后共享价），
     *   winner 从已授权总额 capture 该金额并 release 余量；其余 campaign 全部 release + markLost；
     *   产出单条 allocation（slot_index=0，分配期起点 = 原 window_end_at，Q13）。</li>
     *   <li>{@code AUCTION_NO_BID}：全部 release，不写 allocation。</li>
     * </ul>
     */
    private void settleWindow(PromotionAuctionDecision decision) {
        if (allocationMapper.countByAuctionWindowId(decision.auctionWindowId()) > 0) {
            return;
        }
        PromotionAuctionWindow window = windowMapper.findById(decision.auctionWindowId());
        if (window == null) {
            throw new IllegalStateException("promotion auction window not found: " + decision.auctionWindowId());
        }
        Instant allocationStartAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
        List<PromotionBid> ranked = bidMapper.listActiveBidsByWindowId(
                        window.getId(), allocationStartAt, allocationEndAt)
                .stream()
                .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed()
                        .thenComparingLong(PromotionBid::getId))
                .toList();
        Map<Long, PromotionBidEscrowRecord> escrows = escrowMapper.listActiveByWindowId(window.getId()).stream()
                .collect(Collectors.toMap(PromotionBidEscrowRecord::getCampaignId, Function.identity()));
        if ("AUCTION_NO_BID".equals(decision.decisionType())) {
            for (PromotionBidEscrowRecord escrow : escrows.values()) {
                release(escrow.getBidderUserId(), escrow.getAuthorizedAmount(),
                        window.getId(), escrow.getCampaignId());
            }
            escrowMapper.markClosedByWindowId(window.getId(), decision.decidedAt());
            windowMapper.markSettled(window.getId(), decision.decidedAt());
            cacheService.refreshActiveAllocations(window.getResourceType(), decision.decidedAt());
            return;
        }
        long winnerCampaignId = payloadLong(decision, "winnerCampaignId");
        long winningAmount = payloadLong(decision, "winningAmount");
        if (ranked.isEmpty() || ranked.getFirst().getCampaignId() != winnerCampaignId) {
            throw new IllegalStateException("promotion terminal winner mismatch: auctionWindowId="
                    + decision.auctionWindowId() + ", winnerCampaignId=" + winnerCampaignId
                    + ", rankedTop=" + (ranked.isEmpty() ? "none" : ranked.getFirst().getCampaignId()));
        }
        for (PromotionBid bid : ranked) {
            PromotionBidEscrowRecord escrow = escrows.remove(bid.getCampaignId());
            long authorizedAmount = escrow == null ? bid.getBidAmount() : escrow.getAuthorizedAmount();
            if (bid.getCampaignId() == winnerCampaignId) {
                walletService.captureHoldToPlatform(bid.getBidderUserId(), winningAmount,
                        WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                        settlementBusinessRef(window.getId(), bid.getCampaignId(), "capture"));
                long releaseAmount = authorizedAmount - winningAmount;
                if (releaseAmount > 0) {
                    release(bid, releaseAmount, window.getId());
                }
                bidMapper.markWon(bid.getId(), 0, winningAmount);
                allocationMapper.insert(PromotionSlotAllocation.builder()
                        .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                        .auctionWindowId(window.getId())
                        .resourceType(window.getResourceType())
                        .slotIndex(0)
                        .campaignId(bid.getCampaignId())
                        .postId(bid.getPostId())
                        .bidderUserId(bid.getBidderUserId())
                        .clearingPrice(winningAmount)
                        .allocationStartAt(allocationStartAt)
                        .allocationEndAt(allocationEndAt)
                        .createdAt(decision.decidedAt())
                        .build());
            } else {
                release(bid, authorizedAmount, window.getId());
                bidMapper.markLost(bid.getId());
            }
        }
        for (PromotionBidEscrowRecord unusedEscrow : escrows.values()) {
            release(unusedEscrow.getBidderUserId(), unusedEscrow.getAuthorizedAmount(),
                    window.getId(), unusedEscrow.getCampaignId());
        }
        escrowMapper.markClosedByWindowId(window.getId(), decision.decidedAt());
        windowMapper.markSettled(window.getId(), decision.decidedAt());
        cacheService.refreshActiveAllocations(window.getResourceType(), decision.decidedAt());
    }

    private long payloadLong(PromotionAuctionDecision decision, String key) {
        Object value = decision.payload().get(key);
        if (value == null) {
            throw new IllegalStateException("promotion terminal decision is missing payload field: "
                    + key + ", type=" + decision.decisionType());
        }
        return Long.parseLong(String.valueOf(value));
    }

    private void release(PromotionBid bid, long amount, long windowId) {
        release(bid.getBidderUserId(), amount, windowId, bid.getCampaignId());
    }

    private void release(long bidderUserId, long amount, long windowId, long campaignId) {
        if (amount <= 0) {
            return;
        }
        walletService.releaseHold(bidderUserId, amount,
                WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                settlementBusinessRef(windowId, campaignId, "release"));
    }

    private void requireNextVersion(PromotionAuctionDecision decision, long lastVersion) {
        if (decision.previousVersion() != lastVersion || decision.decisionVersion() != lastVersion + 1) {
            throw new IllegalStateException("promotion decision version gap: auctionWindowId="
                    + decision.auctionWindowId() + ", last=" + lastVersion
                    + ", previous=" + decision.previousVersion()
                    + ", current=" + decision.decisionVersion());
        }
    }

    private void requireMatchingStreamId(PromotionAuctionDecision decision, String streamId) {
        String expected = decision.decisionVersion() + "-0";
        if (!expected.equals(streamId)) {
            throw new IllegalStateException("promotion Stream ID/version mismatch: expected="
                    + expected + ", actual=" + streamId);
        }
    }

    private boolean isDuplicate(PromotionAuctionDecision decision, ProjectionState state) {
        if (decision.decisionVersion() < state.lastDecisionVersion) {
            return true;
        }
        return decision.decisionVersion() == state.lastDecisionVersion
                && java.util.Objects.equals(decision.decisionId(), state.lastDecisionId);
    }

    private ProjectionState loadProjectionState(long auctionWindowId) {
        PromotionProjectionCheckpointRecord checkpoint = checkpointMapper.findByAuctionWindowId(auctionWindowId);
        ProjectionState state = new ProjectionState();
        if (checkpoint != null) {
            state.lastDecisionId = checkpoint.getLastDecisionId();
            state.lastDecisionVersion = checkpoint.getLastDecisionVersion();
            state.lastStreamId = checkpoint.getLastStreamId();
        }
        return state;
    }

    private String holdBusinessRef(PromotionAuctionDecision decision) {
        return "promotion-bprime:escrow:" + decision.auctionWindowId() + ":" + decision.campaignId();
    }

    private String settlementBusinessRef(long windowId, long campaignId, String effect) {
        return "promotion-bprime:" + windowId + ":" + campaignId + ":" + effect;
    }

    private static final class ProjectionState {
        private String lastDecisionId;
        private long lastDecisionVersion;
        private String lastStreamId;
    }
}
