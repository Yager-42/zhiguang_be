package com.tongji.promotion.bprime.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.bprime.mapper.PromotionBidEscrowMapper;
import com.tongji.promotion.bprime.mapper.PromotionProjectionCheckpointMapper;
import com.tongji.promotion.bprime.model.PromotionAuctionDecision;
import com.tongji.promotion.bprime.model.PromotionDecisionProjectionItem;
import com.tongji.promotion.bprime.model.PromotionProjectionCheckpointRecord;
import com.tongji.promotion.mapper.PromotionBidMapper;
import com.tongji.promotion.model.PromotionBid;
import com.tongji.promotion.model.PromotionBidStatus;
import com.tongji.promotion.settlement.PromotionAuctionSettlementModule;
import com.tongji.promotion.settlement.PromotionAuctionTerminalInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 严格按 Redis Stream 版本将竞价事实投影到 MySQL。
 */
@Service
public class PromotionDecisionProjectionService {

    private final PromotionProjectionCheckpointMapper checkpointMapper;
    private final PromotionBidEscrowMapper escrowMapper;
    private final PromotionBidMapper bidMapper;
    private final IdService idService;
    private final PromotionAuctionSettlementModule settlementModule;
    public PromotionDecisionProjectionService(PromotionProjectionCheckpointMapper checkpointMapper,
                                              PromotionBidEscrowMapper escrowMapper,
                                              PromotionBidMapper bidMapper,
                                              PromotionAuctionSettlementModule settlementModule,
                                              IdService idService) {
        this.checkpointMapper = checkpointMapper;
        this.escrowMapper = escrowMapper;
        this.bidMapper = bidMapper;
        this.settlementModule = settlementModule;
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
        PromotionAuctionDecision.TerminalFacts facts = decision.terminalFacts();
        boolean sold = decision.kind() == com.tongji.promotion.bprime.model.PromotionDecisionType.AUCTION_SOLD;
        settlementModule.settle(new PromotionAuctionTerminalInput(
                decision.auctionWindowId(),
                sold ? PromotionAuctionTerminalInput.Kind.SOLD : PromotionAuctionTerminalInput.Kind.NO_BID,
                facts.winnerCampaignId().orElse(null),
                facts.winningAmount().orElse(null),
                decision.decidedAt()));
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


    private static final class ProjectionState {
        private String lastDecisionId;
        private long lastDecisionVersion;
        private String lastStreamId;
    }
}
