package com.tongji.reconciliation.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.service.PromotionAuctionSettlementPlan;
import com.tongji.promotion.service.PromotionAuctionSettlementPlanner;
import com.tongji.reconciliation.executor.PromotionWalletEffectRepairPayload;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletLedgerEntry;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class PromotionAuctionCompensationService {

    private final PromotionBidMapper bidMapper;
    private final PromotionSlotAllocationMapper allocationMapper;
    private final WalletLedgerMapper walletLedgerMapper;
    private final PromotionAuctionSettlementPlanner settlementPlanner;
    private final ObjectMapper objectMapper;

    public PromotionAuctionCompensationService(PromotionBidMapper bidMapper,
                                               PromotionSlotAllocationMapper allocationMapper,
                                               WalletLedgerMapper walletLedgerMapper,
                                               PromotionAuctionSettlementPlanner settlementPlanner,
                                               ObjectMapper objectMapper) {
        this.bidMapper = bidMapper;
        this.allocationMapper = allocationMapper;
        this.walletLedgerMapper = walletLedgerMapper;
        this.settlementPlanner = settlementPlanner;
        this.objectMapper = objectMapper;
    }

    public List<ReconciliationTask> reconcileWindow(PromotionAuctionWindow window,
                                                    ReconciliationService reconciliationService) {
        List<PromotionBid> bids = activeBids(window);
        PromotionAuctionSettlementPlan plan = settlementPlanner.plan(window, bids);
        return new WindowAnalyzer(window, plan, reconciliationService).run();
    }

    public void scanWindow(PromotionAuctionWindow window, ReconciliationService reconciliationService) {
        List<PromotionBid> bids = activeBids(window);
        PromotionAuctionSettlementPlan plan = settlementPlanner.plan(window, bids);
        new WindowAnalyzer(window, plan, reconciliationService).run();
    }

    private List<PromotionBid> activeBids(PromotionAuctionWindow window) {
        Instant allocationStartAt = window.getWindowEndAt();
        long spanSeconds = window.getWindowEndAt().getEpochSecond() - window.getWindowStartAt().getEpochSecond();
        Instant allocationEndAt = allocationStartAt.plusSeconds(spanSeconds);
        return bidMapper.listSettledBidsByWindowId(window.getId(), allocationStartAt, allocationEndAt);
    }

    private final class WindowAnalyzer {
        private final PromotionAuctionWindow window;
        private final PromotionAuctionSettlementPlan plan;
        private final ReconciliationService reconciliationService;
        private final List<ReconciliationTask> tasks = new ArrayList<>();

        private WindowAnalyzer(PromotionAuctionWindow window, PromotionAuctionSettlementPlan plan,
                               ReconciliationService reconciliationService) {
            this.window = window;
            this.plan = plan;
            this.reconciliationService = reconciliationService;
        }

        private List<ReconciliationTask> run() {
            scanAllocation();
            scanWalletEffects();
            return tasks;
        }

        private void scanAllocation() {
            List<PromotionSlotAllocation> actual = allocationMapper.listByAuctionWindowId(window.getId());
            if (actual.isEmpty() && !plan.winners().isEmpty()) {
                add(reconciliationService.createTaskIfAbsent(
                        ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                        ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                        window.getId()
                ));
                return;
            }
            if (!actual.isEmpty() && !allocationMatches(actual)) {
                add(reconciliationService.createDeadTaskIfAbsent(
                        ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                        ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                        window.getId(),
                        "promotion allocation is partially present or inconsistent for settled window " + window.getId()
                ));
            }
        }

        private boolean allocationMatches(List<PromotionSlotAllocation> actual) {
            if (actual.size() != plan.winners().size()) {
                return false;
            }
            for (int i = 0; i < actual.size(); i++) {
                PromotionSlotAllocation row = actual.get(i);
                PromotionAuctionSettlementPlan.Winner expected = plan.winners().get(i);
                if (row.getSlotIndex() != expected.slotIndex()
                        || row.getCampaignId() != expected.bid().getCampaignId()
                        || row.getBidderUserId() != expected.bid().getBidderUserId()
                        || row.getClearingPrice() != expected.clearingPrice()) {
                    return false;
                }
            }
            return true;
        }

        private void scanWalletEffects() {
            for (PromotionAuctionSettlementPlan.WalletEffect effect : plan.walletEffects()) {
                List<WalletLedgerEntry> ledgerGroup = walletLedgerMapper.findByBusinessRef(effect.businessRef());
                String payload = payload(effect);
                if (ledgerGroup.isEmpty()) {
                    add(reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR,
                            ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                            window.getId(),
                            payload
                    ));
                } else if (!ledgerMatches(ledgerGroup, effect)) {
                    add(reconciliationService.createDeadTaskIfAbsent(
                            ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR,
                            ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                            window.getId(),
                            payload,
                            "promotion wallet effect conflicts with existing businessRef " + effect.businessRef()
                    ));
                }
            }
        }

        private boolean ledgerMatches(List<WalletLedgerEntry> ledgerGroup,
                                      PromotionAuctionSettlementPlan.WalletEffect effect) {
            if (ledgerGroup.size() != 1) {
                return false;
            }
            WalletLedgerEntry row = ledgerGroup.getFirst();
            return row.getOwnerUserId() == effect.ownerUserId()
                    && row.getAmount() == effect.amount()
                    && row.getReason() == effect.reason()
                    && row.getBusinessType() == effect.businessType()
                    && row.getDirection() == effect.direction()
                    && equals(row.getCounterpartyUserId(), effect.counterpartyUserId())
                    && equals(row.getEscrowId(), effect.escrowId())
                    && row.getAvailableDelta() == effect.availableDelta()
                    && row.getHeldDelta() == effect.heldDelta()
                    && row.getEscrowedDelta() == effect.escrowedDelta();
        }

        private String payload(PromotionAuctionSettlementPlan.WalletEffect effect) {
            try {
                return objectMapper.writeValueAsString(new PromotionWalletEffectRepairPayload(
                        effect.effectType(),
                        effect.ownerUserId(),
                        effect.amount(),
                        effect.reason(),
                        effect.businessType(),
                        effect.direction(),
                        effect.counterpartyUserId(),
                        effect.escrowId(),
                        effect.availableDelta(),
                        effect.heldDelta(),
                        effect.escrowedDelta(),
                        effect.businessRef()
                ));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to serialize promotion wallet repair payload", e);
            }
        }

        private boolean equals(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }

        private void add(ReconciliationTask task) {
            if (task != null) {
                tasks.add(task);
            }
        }
    }
}
