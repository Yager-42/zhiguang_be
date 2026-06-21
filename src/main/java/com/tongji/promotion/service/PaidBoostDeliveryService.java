package com.tongji.promotion.service;

import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.promotion.config.PaidBoostProperties;
import com.tongji.promotion.mapper.PaidBoostDeliveryMapper;
import com.tongji.promotion.model.PaidBoostCampaign;
import com.tongji.promotion.model.PaidBoostDelivery;
import com.tongji.promotion.model.PaidBoostDeliveryStatus;
import com.tongji.promotion.model.PaidBoostChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * boost 投放事实记录：读路径只 upsert delivery 事实，**不直接扣 wallet**。
 * <p>同一 (campaign, bucket, viewer) 在同 bucket 内重复送达只累加 delivery_count（mapper ON DUPLICATE KEY），
 * 不新增第二条计费事实；实际扣费由 {@link PaidBoostSettlementService} 定时聚合结算。
 * 本 service 只负责记录与聚合，不承担 active campaign 读查询（由 {@link PaidBoostCacheService} 提供）。</p>
 */
@Service
public class PaidBoostDeliveryService {

    private static final String SETTLE_REF_PREFIX = "paid-boost:";
    private static final String SETTLE_REF_MIDDLE = ":spend:";
    private static final DateTimeFormatter BUCKET_LABEL = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final PaidBoostDeliveryMapper deliveryMapper;
    private final IdService idService;
    private final PaidBoostProperties properties;
    private final Clock clock;

    @Autowired
    public PaidBoostDeliveryService(PaidBoostDeliveryMapper deliveryMapper,
                                    IdService idService,
                                    PaidBoostProperties properties) {
        this(deliveryMapper, idService, properties, Clock.systemUTC());
    }

    PaidBoostDeliveryService(PaidBoostDeliveryMapper deliveryMapper,
                             IdService idService,
                             PaidBoostProperties properties,
                             Clock clock) {
        this.deliveryMapper = deliveryMapper;
        this.idService = idService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void recordDeliveries(PaidBoostChannel channel, Long viewerUserId, List<PaidBoostCampaign> deliveredCampaigns) {
        if (deliveredCampaigns == null || deliveredCampaigns.isEmpty() || viewerUserId == null) {
            return;
        }
        Instant now = clock.instant();
        Instant bucketStart = alignBucket(now);
        for (PaidBoostCampaign campaign : deliveredCampaigns) {
            if (campaign == null) {
                continue;
            }
            long remaining = campaign.getBudgetTotal() - campaign.getBudgetConsumed();
            if (remaining <= 0) {
                continue;
            }
            PaidBoostDelivery delivery = PaidBoostDelivery.builder()
                    .id(idService.nextId(IdNamespace.ADMIN_OPERATION))
                    .campaignId(campaign.getId())
                    .channel(channel)
                    .postId(campaign.getPostId())
                    .viewerUserId(viewerUserId)
                    .deliveryBucketStartAt(bucketStart)
                    .deliveryCount(1)
                    .unitPriceSnapshot(campaign.getUnitPrice())
                    .capturedAmount(0L)
                    .settleBusinessRef(settleRef(campaign.getId(), bucketStart, viewerUserId))
                    .status(PaidBoostDeliveryStatus.PENDING)
                    .settledAt(null)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            deliveryMapper.upsertPending(delivery);
        }
    }

    private Instant alignBucket(Instant now) {
        long bucketSeconds = Math.max(1L, properties.getDeliveryBucketSeconds());
        long aligned = (now.getEpochSecond() / bucketSeconds) * bucketSeconds;
        return Instant.ofEpochSecond(aligned);
    }

    private String settleRef(long campaignId, Instant bucketStart, long viewerUserId) {
        return SETTLE_REF_PREFIX + campaignId + SETTLE_REF_MIDDLE
                + BUCKET_LABEL.withZone(ZoneOffset.UTC).format(bucketStart)
                + ":" + viewerUserId;
    }
}
