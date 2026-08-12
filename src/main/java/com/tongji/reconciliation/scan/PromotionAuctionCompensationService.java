package com.tongji.reconciliation.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.mapper.PromotionSlotAllocationMapper;
import com.tongji.promotion.model.PromotionAuctionWindow;
import com.tongji.promotion.model.PromotionSlotAllocation;
import com.tongji.promotion.settlement.PromotionAuctionSettlementFacts;
import com.tongji.promotion.settlement.PromotionAuctionSettlementModule;
import com.tongji.reconciliation.executor.PromotionWalletEffectRepairPayload;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTask;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.wallet.mapper.WalletLedgerMapper;
import com.tongji.wallet.model.WalletLedgerEntry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PromotionAuctionCompensationService {

    private final PromotionSlotAllocationMapper allocationMapper;
    private final WalletLedgerMapper walletLedgerMapper;
    private final PromotionAuctionSettlementModule settlementModule;
    private final ObjectMapper objectMapper;

    public PromotionAuctionCompensationService(PromotionSlotAllocationMapper allocationMapper,
                                               WalletLedgerMapper walletLedgerMapper,
                                               PromotionAuctionSettlementModule settlementModule,
                                               ObjectMapper objectMapper) {
        this.allocationMapper = allocationMapper;
        this.walletLedgerMapper = walletLedgerMapper;
        this.settlementModule = settlementModule;
        this.objectMapper = objectMapper;
    }

    public List<ReconciliationTask> reconcileWindow(PromotionAuctionWindow window,
                                                    ReconciliationService reconciliationService) {
        return new WindowAnalyzer(window, settlementModule.expectedSettlement(window.getId()),
                reconciliationService).run();
    }

    public void scanWindow(PromotionAuctionWindow window, ReconciliationService reconciliationService) {
        new WindowAnalyzer(window, settlementModule.expectedSettlement(window.getId()),
                reconciliationService).run();
    }

    private final class WindowAnalyzer {
        private final PromotionAuctionWindow window;
        private final PromotionAuctionSettlementFacts facts;
        private final ReconciliationService reconciliationService;
        private final List<ReconciliationTask> tasks = new ArrayList<>();

        private WindowAnalyzer(PromotionAuctionWindow window,
                               PromotionAuctionSettlementFacts facts,
                               ReconciliationService reconciliationService) {
            this.window = window;
            this.facts = facts;
            this.reconciliationService = reconciliationService;
        }

        private List<ReconciliationTask> run() {
            scanAllocation();
            scanWalletEffects();
            return List.copyOf(tasks);
        }

        private void scanAllocation() {
            List<PromotionSlotAllocation> actual = allocationMapper.listByAuctionWindowId(window.getId());
            if (facts.allocation().isEmpty()) {
                if (!actual.isEmpty()) {
                    add(reconciliationService.createDeadTaskIfAbsent(
                            ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                            ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                            window.getId(),
                            "promotion no-bid settlement has unexpected allocation for window " + window.getId()));
                }
                return;
            }
            if (actual.isEmpty()) {
                add(reconciliationService.createTaskIfAbsent(
                        ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                        ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                        window.getId()));
                return;
            }
            if (!allocationMatches(actual, facts.allocation().orElseThrow())) {
                add(reconciliationService.createDeadTaskIfAbsent(
                        ReconciliationTaskType.PROMOTION_ALLOCATION_REBUILD,
                        ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                        window.getId(),
                        "promotion allocation is partially present or inconsistent for settled window " + window.getId()));
            }
        }

        private boolean allocationMatches(List<PromotionSlotAllocation> actual,
                                          PromotionAuctionSettlementFacts.Allocation expected) {
            if (actual.size() != 1) {
                return false;
            }
            PromotionSlotAllocation row = actual.getFirst();
            return row.getSlotIndex() == expected.slotIndex()
                    && row.getCampaignId() == expected.campaignId()
                    && row.getBidderUserId() == expected.bidderUserId()
                    && row.getClearingPrice() == expected.clearingPrice()
                    && java.util.Objects.equals(row.getAllocationStartAt(), expected.allocationStartAt())
                    && java.util.Objects.equals(row.getAllocationEndAt(), expected.allocationEndAt());
        }

        private void scanWalletEffects() {
            for (PromotionAuctionSettlementFacts.WalletEffect effect : facts.walletEffects()) {
                List<WalletLedgerEntry> ledgerGroup = walletLedgerMapper.findByBusinessRef(effect.businessRef());
                String payload = payload(effect);
                if (ledgerGroup.isEmpty()) {
                    add(reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR,
                            ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                            window.getId(), payload));
                } else if (!ledgerMatches(ledgerGroup, effect)) {
                    add(reconciliationService.createDeadTaskIfAbsent(
                            ReconciliationTaskType.PROMOTION_WALLET_EFFECT_REPAIR,
                            ReconciliationTargetType.PROMOTION_AUCTION_WINDOW,
                            window.getId(), payload,
                            "promotion wallet effect conflicts with existing businessRef " + effect.businessRef()));
                }
            }
        }

        private boolean ledgerMatches(List<WalletLedgerEntry> ledgerGroup,
                                      PromotionAuctionSettlementFacts.WalletEffect effect) {
            if (ledgerGroup.size() != 1) {
                return false;
            }
            WalletLedgerEntry row = ledgerGroup.getFirst();
            return row.getOwnerUserId() == effect.ownerUserId()
                    && row.getAmount() == effect.amount()
                    && row.getReason() == effect.reason()
                    && row.getBusinessType() == effect.businessType()
                    && row.getDirection() == effect.direction()
                    && java.util.Objects.equals(row.getCounterpartyUserId(), effect.counterpartyUserId())
                    && java.util.Objects.equals(row.getEscrowId(), effect.escrowId())
                    && row.getAvailableDelta() == effect.availableDelta()
                    && row.getHeldDelta() == effect.heldDelta()
                    && row.getEscrowedDelta() == effect.escrowedDelta();
        }

        private String payload(PromotionAuctionSettlementFacts.WalletEffect effect) {
            try {
                return objectMapper.writeValueAsString(new PromotionWalletEffectRepairPayload(
                        effect.effectType().name(), effect.ownerUserId(), effect.amount(), effect.reason(),
                        effect.businessType(), effect.direction(), effect.counterpartyUserId(), effect.escrowId(),
                        effect.availableDelta(), effect.heldDelta(), effect.escrowedDelta(), effect.businessRef()));
            } catch (Exception e) {
                throw new IllegalStateException("Unable to serialize promotion wallet repair payload", e);
            }
        }

        private void add(ReconciliationTask task) {
            if (task != null) {
                tasks.add(task);
            }
        }
    }
}
