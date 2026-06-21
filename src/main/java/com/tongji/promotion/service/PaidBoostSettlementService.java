package com.tongji.promotion.service;

import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.mapper.PaidBoostCampaignMapper;
import com.tongji.promotion.mapper.PaidBoostDeliveryMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostCampaignStatus;
import com.tongji.promotion.model.PaidBoostDelivery;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import com.tongji.wallet.service.WalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * boost 预算结算：定时聚合 PENDING delivery 扣费到平台、关闭到期活动并释放剩余冻结。
 * <p>结算只取已关闭的 bucket（cutoff = now - deliveryBucketSeconds），避免结算仍可能累计的开放 bucket；
 * capture 额取 {@code min(剩余预算, deliveryCount * unitPriceSnapshot)}，保证不超扣。
 * 扣费用 {@link WalletLedgerReason#PAID_BOOST_CAPTURE}，释放用 {@link WalletLedgerReason#PAID_BOOST_RELEASE}，
 * 与拍卖 {@code PROMOTION_BID_*} 分账。所有账务走 wallet business_ref 幂等。</p>
 */
@Service
public class PaidBoostSettlementService {

    private static final String RELEASE_REF_PREFIX = "paid-boost:";
    private static final String RELEASE_REF_SUFFIX = ":release";

    private final PaidBoostDeliveryMapper deliveryMapper;
    private final PaidBoostCampaignMapper campaignMapper;
    private final WalletService walletService;
    private final PaidBoostProperties properties;

    public PaidBoostSettlementService(PaidBoostDeliveryMapper deliveryMapper,
                                      PaidBoostCampaignMapper campaignMapper,
                                      WalletService walletService,
                                      PaidBoostProperties properties) {
        this.deliveryMapper = deliveryMapper;
        this.campaignMapper = campaignMapper;
        this.walletService = walletService;
        this.properties = properties;
    }

    @Transactional
    public void settlePendingDeliveries(Instant now, int batchSize) {
        Instant cutoff = now.minusSeconds(Math.max(1L, properties.getDeliveryBucketSeconds()));
        for (PaidBoostDelivery delivery : deliveryMapper.listPendingBefore(cutoff, batchSize)) {
            PaidBoostCampaign campaign = campaignMapper.findById(delivery.getCampaignId());
            if (campaign == null || campaign.getStatus() != PaidBoostCampaignStatus.ACTIVE) {
                continue;
            }
            long remaining = campaign.getBudgetTotal() - campaign.getBudgetConsumed();
            long planned = (long) delivery.getDeliveryCount() * delivery.getUnitPriceSnapshot();
            long captured = Math.min(remaining, planned);
            if (captured <= 0) {
                deliveryMapper.markSettledWithAmount(delivery.getId(), 0L, now);
                continue;
            }
            walletService.captureHoldToPlatform(campaign.getCreatorUserId(), captured,
                    WalletLedgerReason.PAID_BOOST_CAPTURE, WalletBusinessType.PROMOTION, delivery.getSettleBusinessRef());
            campaignMapper.increaseBudgetConsumed(campaign.getId(), captured, now);
            deliveryMapper.markSettledWithAmount(delivery.getId(), captured, now);
        }
    }

    @Transactional
    public void closeExpiredCampaigns(Instant now, int batchSize) {
        for (PaidBoostCampaign campaign : campaignMapper.listClosable(now, batchSize)) {
            long remaining = campaign.getBudgetTotal() - campaign.getBudgetConsumed();
            if (remaining > 0) {
                walletService.releaseHold(campaign.getCreatorUserId(), remaining,
                        WalletLedgerReason.PAID_BOOST_RELEASE, WalletBusinessType.PROMOTION, releaseRef(campaign.getId()));
            }
            campaignMapper.markClosed(campaign.getId(), now, now);
        }
    }

    private static String releaseRef(long campaignId) {
        return RELEASE_REF_PREFIX + campaignId + RELEASE_REF_SUFFIX;
    }
}
