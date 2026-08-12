package com.tongji.promotion.settlement;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.model.PromotionBidEscrowRecord;
import com.tongji.promotion.mapper.PromotionAuctionWindowMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionAuctionWindowStatus;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAllocationCacheService;
import com.tongji.wallet.config.WalletProperties;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerDirection;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PromotionAuctionSettlementModule {

    private final PromotionAuctionWindowMapper windowMapper;
    private final PromotionBidMapper bidMapper;
    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final WalletService walletService;
    private final IdService idService;
    private final PromotionAllocationCacheService cacheService;
    private final WalletProperties walletProperties;

    public PromotionAuctionSettlementModule(PromotionAuctionWindowMapper windowMapper,
                                            PromotionBidMapper bidMapper,
                                            PromotionBidEscrowMapper escrowMapper,
                                            PromotionSlotAllocationMapper allocationMapper,
                                            WalletService walletService,
                                            IdService idService,
                                            PromotionAllocationCacheService cacheService,
                                            WalletProperties walletProperties) {
        this.windowMapper = windowMapper;
        this.bidMapper = bidMapper;
        this.escrowMapper = escrowMapper;
        this.allocationMapper = allocationMapper;
        this.walletService = walletService;
        this.idService = idService;
        this.cacheService = cacheService;
        this.walletProperties = walletProperties;
    }

    public PromotionAuctionSettlementFacts settle(PromotionAuctionTerminalInput input) {
        PromotionAuctionWindow window = windowMapper.findByIdForUpdate(input.auctionWindowId());
        if (window == null) {
            throw new IllegalStateException("promotion auction window not found: " + input.auctionWindowId());
        }
        if (window.getStatus() == PromotionAuctionWindowStatus.SETTLED) {
            PromotionAuctionSettlementFacts existing = expectedSettlement(window);
            requireMatchingTerminal(input, existing);
            return existing;
        }
        if (window.getStatus() != PromotionAuctionWindowStatus.OPEN) {
            throw new IllegalStateException("promotion auction window is not open: " + input.auctionWindowId());
        }
        PromotionAuctionSettlementFacts facts = deriveOpenFacts(window, input);
        apply(facts, input.decidedAt());
        return facts;
    }

    public PromotionAuctionSettlementFacts expectedSettlement(long auctionWindowId) {
        PromotionAuctionWindow window = windowMapper.findById(auctionWindowId);
        if (window == null) {
            throw new IllegalStateException("promotion auction window not found: " + auctionWindowId);
        }
        return expectedSettlement(window);
    }

    private PromotionAuctionSettlementFacts expectedSettlement(PromotionAuctionWindow window) {
        long auctionWindowId = window.getId();
        if (window.getStatus() != PromotionAuctionWindowStatus.SETTLED) {
            throw new IllegalStateException("promotion auction window is not settled: " + auctionWindowId);
        }
        Instant allocationStartAt = window.getWindowEndAt();
        long durationSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(durationSeconds);
        List<PromotionBid> settled = bidMapper.listSettledBidsByWindowId(
                        window.getId(), allocationStartAt, allocationEndAt).stream()
                .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed()
                        .thenComparingLong(PromotionBid::getId))
                .toList();
        Map<Long, PromotionBidEscrowRecord> escrows = escrowMap(escrowMapper.listByWindowId(window.getId()));
        List<PromotionBid> winners = settled.stream()
                .filter(bid -> bid.getStatus() == com.tongji.promotion.model.PromotionBidStatus.WON)
                .toList();
        if (winners.isEmpty()) {
            if (!settled.isEmpty()) {
                throw new IllegalStateException("promotion settled bids have no winner: " + auctionWindowId);
            }
            return noBidFacts(window, escrows.values().stream().toList());
        }
        if (winners.size() != 1 || winners.getFirst() != settled.getFirst()
                || winners.getFirst().getClearingPrice() == null
                || winners.getFirst().getSlotIndex() == null
                || winners.getFirst().getSlotIndex() != 0) {
            throw new IllegalStateException("promotion settled winner facts conflict: " + auctionWindowId);
        }
        PromotionBid winner = winners.getFirst();
        return soldFacts(window, new PromotionAuctionTerminalInput(window.getId(),
                        PromotionAuctionTerminalInput.Kind.SOLD, winner.getCampaignId(), winner.getClearingPrice(),
                        window.getSettledAt()), settled, escrows, allocationStartAt, allocationEndAt);
    }

    private void requireMatchingTerminal(PromotionAuctionTerminalInput input,
                                         PromotionAuctionSettlementFacts existing) {
        if (input.kind() == PromotionAuctionTerminalInput.Kind.NO_BID) {
            if (existing.terminalKind() != PromotionAuctionSettlementFacts.TerminalKind.NO_BID) {
                throw new IllegalStateException("promotion duplicate terminal conflicts with settled facts: "
                        + input.auctionWindowId());
            }
            return;
        }
        PromotionAuctionSettlementFacts.Winner winner = existing.winner()
                .orElseThrow(() -> new IllegalStateException(
                        "promotion duplicate sold terminal conflicts with no-bid settlement: " + input.auctionWindowId()));
        if (!java.util.Objects.equals(input.winnerCampaignId(), winner.campaignId())
                || !java.util.Objects.equals(input.winningAmount(), winner.winningAmount())) {
            throw new IllegalStateException("promotion duplicate terminal conflicts with settled facts: "
                    + input.auctionWindowId());
        }
    }

    public PromotionAuctionSettlementFacts rebuildMissingAllocation(long auctionWindowId) {
        PromotionAuctionSettlementFacts facts = expectedSettlement(auctionWindowId);
        PromotionAuctionSettlementFacts.Allocation allocation = facts.allocation()
                .orElseThrow(() -> new IllegalStateException(
                        "promotion no-bid settlement must not have allocation: " + auctionWindowId));
        int existing = allocationMapper.countByAuctionWindowId(auctionWindowId);
        if (existing != 0) {
            throw new IllegalStateException(
                    "promotion allocation rebuild requires wholly missing allocation: existing=" + existing);
        }
        allocationMapper.insert(PromotionSlotAllocation.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .auctionWindowId(auctionWindowId)
                .resourceType(allocation.resourceType())
                .slotIndex(allocation.slotIndex())
                .campaignId(allocation.campaignId())
                .postId(allocation.postId())
                .bidderUserId(allocation.bidderUserId())
                .clearingPrice(allocation.clearingPrice())
                .allocationStartAt(allocation.allocationStartAt())
                .allocationEndAt(allocation.allocationEndAt())
                .createdAt(facts.window().getSettledAt())
                .build());
        return facts;
    }

    private Map<Long, PromotionBidEscrowRecord> escrowMap(List<PromotionBidEscrowRecord> records) {
        Map<Long, PromotionBidEscrowRecord> escrows = new LinkedHashMap<>();
        for (PromotionBidEscrowRecord escrow : records) {
            if (escrows.put(escrow.getCampaignId(), escrow) != null) {
                throw new IllegalStateException("duplicate promotion escrow: campaignId=" + escrow.getCampaignId());
            }
        }
        return escrows;
    }

    private PromotionAuctionSettlementFacts deriveOpenFacts(PromotionAuctionWindow window,
                                                              PromotionAuctionTerminalInput input) {
        Instant allocationStartAt = window.getWindowEndAt();
        long durationSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(durationSeconds);
        List<PromotionBid> ranked = bidMapper.listActiveBidsByWindowId(
                        window.getId(), allocationStartAt, allocationEndAt).stream()
                .sorted(Comparator.comparingLong(PromotionBid::getBidAmount).reversed()
                        .thenComparingLong(PromotionBid::getId))
                .toList();
        Map<Long, PromotionBidEscrowRecord> escrows = new LinkedHashMap<>();
        for (PromotionBidEscrowRecord escrow : escrowMapper.listActiveByWindowId(window.getId())) {
            if (escrows.put(escrow.getCampaignId(), escrow) != null) {
                throw new IllegalStateException("duplicate active promotion escrow: campaignId=" + escrow.getCampaignId());
            }
        }
        if (input.kind() == PromotionAuctionTerminalInput.Kind.NO_BID) {
            if (!ranked.isEmpty()) {
                throw new IllegalStateException("promotion no-bid terminal conflicts with active bids: " + window.getId());
            }
            return noBidFacts(window, escrows.values().stream().toList());
        }
        return soldFacts(window, input, ranked, escrows, allocationStartAt, allocationEndAt);
    }

    private PromotionAuctionSettlementFacts soldFacts(PromotionAuctionWindow window,
                                                       PromotionAuctionTerminalInput input,
                                                       List<PromotionBid> ranked,
                                                       Map<Long, PromotionBidEscrowRecord> escrows,
                                                       Instant allocationStartAt,
                                                       Instant allocationEndAt) {
        if (input.winnerCampaignId() == null || input.winningAmount() == null || input.winningAmount() <= 0) {
            throw new IllegalStateException("promotion sold terminal is missing winner facts: " + window.getId());
        }
        if (ranked.isEmpty() || ranked.getFirst().getCampaignId() != input.winnerCampaignId()) {
            throw new IllegalStateException("promotion terminal winner mismatch: auctionWindowId=" + window.getId());
        }
        PromotionBid winnerBid = ranked.getFirst();
        if (winnerBid.getBidAmount() != input.winningAmount()) {
            throw new IllegalStateException("promotion terminal winning amount mismatch: auctionWindowId=" + window.getId());
        }
        List<PromotionAuctionSettlementFacts.WalletEffect> effects = new ArrayList<>();
        List<PromotionBid> losers = new ArrayList<>();
        for (PromotionBid bid : ranked) {
            PromotionBidEscrowRecord escrow = escrows.remove(bid.getCampaignId());
            requireMatchingEscrow(bid, escrow);
            if (bid == winnerBid) {
                if (escrow.getAuthorizedAmount() < input.winningAmount()) {
                    throw new IllegalStateException("promotion winner authorization is insufficient: campaignId=" + bid.getCampaignId());
                }
                effects.add(capture(window.getId(), bid.getCampaignId(), bid.getBidderUserId(), input.winningAmount()));
                addRelease(effects, window.getId(), bid.getCampaignId(), bid.getBidderUserId(),
                        escrow.getAuthorizedAmount() - input.winningAmount());
            } else {
                losers.add(bid);
                addRelease(effects, window.getId(), bid.getCampaignId(), bid.getBidderUserId(), escrow.getAuthorizedAmount());
            }
        }
        for (PromotionBidEscrowRecord escrow : escrows.values()) {
            addRelease(effects, window.getId(), escrow.getCampaignId(), escrow.getBidderUserId(),
                    escrow.getAuthorizedAmount());
        }
        PromotionAuctionSettlementFacts.Winner winner = new PromotionAuctionSettlementFacts.Winner(
                winnerBid, winnerBid.getCampaignId(), input.winningAmount());
        PromotionAuctionSettlementFacts.Allocation allocation = new PromotionAuctionSettlementFacts.Allocation(
                window.getResourceType(), 0, winnerBid.getCampaignId(), winnerBid.getPostId(),
                winnerBid.getBidderUserId(), input.winningAmount(), allocationStartAt, allocationEndAt);
        return new PromotionAuctionSettlementFacts(window, PromotionAuctionSettlementFacts.TerminalKind.SOLD,
                Optional.of(winner), List.copyOf(losers), List.copyOf(effects), Optional.of(allocation));
    }

    private PromotionAuctionSettlementFacts noBidFacts(PromotionAuctionWindow window,
                                                        List<PromotionBidEscrowRecord> escrows) {
        List<PromotionAuctionSettlementFacts.WalletEffect> effects = new ArrayList<>();
        for (PromotionBidEscrowRecord escrow : escrows) {
            addRelease(effects, window.getId(), escrow.getCampaignId(), escrow.getBidderUserId(),
                    escrow.getAuthorizedAmount());
        }
        return new PromotionAuctionSettlementFacts(window, PromotionAuctionSettlementFacts.TerminalKind.NO_BID,
                Optional.empty(), List.of(), List.copyOf(effects), Optional.empty());
    }

    private void requireMatchingEscrow(PromotionBid bid, PromotionBidEscrowRecord escrow) {
        if (escrow == null || escrow.getBidderUserId() != bid.getBidderUserId()) {
            throw new IllegalStateException("promotion bid has no matching active escrow: campaignId=" + bid.getCampaignId());
        }
    }

    private void apply(PromotionAuctionSettlementFacts facts, Instant settledAt) {
        for (PromotionAuctionSettlementFacts.WalletEffect effect : facts.walletEffects()) {
            if (effect.effectType() == PromotionAuctionSettlementFacts.EffectType.CAPTURE) {
                walletService.captureHoldToPlatform(effect.ownerUserId(), effect.amount(), effect.reason(),
                        effect.businessType(), effect.businessRef());
            } else {
                walletService.releaseHold(effect.ownerUserId(), effect.amount(), effect.reason(),
                        effect.businessType(), effect.businessRef());
            }
        }
        facts.winner().ifPresent(winner -> bidMapper.markWon(winner.bid().getId(), 0, winner.winningAmount()));
        for (PromotionBid loser : facts.losers()) {
            bidMapper.markLost(loser.getId());
        }
        facts.allocation().ifPresent(allocation -> allocationMapper.insert(PromotionSlotAllocation.builder()
                .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                .auctionWindowId(facts.window().getId())
                .resourceType(allocation.resourceType())
                .slotIndex(allocation.slotIndex())
                .campaignId(allocation.campaignId())
                .postId(allocation.postId())
                .bidderUserId(allocation.bidderUserId())
                .clearingPrice(allocation.clearingPrice())
                .allocationStartAt(allocation.allocationStartAt())
                .allocationEndAt(allocation.allocationEndAt())
                .createdAt(settledAt)
                .build()));
        escrowMapper.markClosedByWindowId(facts.window().getId(), settledAt);
        if (windowMapper.markSettledIfOpen(facts.window().getId(), settledAt) != 1) {
            throw new IllegalStateException("promotion auction window state transition failed: " + facts.window().getId());
        }
        refreshAllocationCacheAfterCommit(facts.window(), settledAt);
    }

    private void refreshAllocationCacheAfterCommit(PromotionAuctionWindow window, Instant settledAt) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            cacheService.refreshActiveAllocations(window.getResourceType(), settledAt);
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        cacheService.refreshActiveAllocations(window.getResourceType(), settledAt);
                    }
                });
    }

    private PromotionAuctionSettlementFacts.WalletEffect capture(long windowId, long campaignId,
                                                                  long bidderUserId, long amount) {
        return new PromotionAuctionSettlementFacts.WalletEffect(
                PromotionAuctionSettlementFacts.EffectType.CAPTURE, bidderUserId, amount,
                WalletLedgerReason.PROMOTION_BPRIME_CAPTURE, WalletBusinessType.PROMOTION,
                WalletLedgerDirection.DEBIT, walletProperties.getPlatformUserId(), null,
                0L, -amount, 0L, businessRef(windowId, campaignId, "capture"));
    }

    private void addRelease(List<PromotionAuctionSettlementFacts.WalletEffect> effects,
                            long windowId, long campaignId, long bidderUserId, long amount) {
        if (amount <= 0) {
            return;
        }
        effects.add(new PromotionAuctionSettlementFacts.WalletEffect(
                PromotionAuctionSettlementFacts.EffectType.RELEASE, bidderUserId, amount,
                WalletLedgerReason.PROMOTION_BPRIME_RELEASE, WalletBusinessType.PROMOTION,
                WalletLedgerDirection.CREDIT, null, null, amount, -amount, 0L,
                businessRef(windowId, campaignId, "release")));
    }

    private String businessRef(long windowId, long campaignId, String effect) {
        return "promotion-bprime:" + windowId + ":" + campaignId + ":" + effect;
    }
}
