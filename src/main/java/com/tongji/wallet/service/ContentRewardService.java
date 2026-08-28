package com.tongji.wallet.service;

import com.tongji.wallet.config.ContentRewardProperties;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容创作积分奖励发放服务。
 *
 * <p>发帖奖励由 Kafka 派生消费调用严格入口，异常必须向上冒泡以触发重试；评论奖励保留
 * {@link Propagation#REQUIRES_NEW} 与失败不阻塞评论主流程的既有语义。两类奖励都依赖
 * {@link WalletService#grant} 的 businessRef 保证重复消费不重复入账。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentRewardService {

    private final WalletService walletService;
    private final ContentRewardProperties properties;

    /**
     * 为已发布知文发放奖励；钱包故障向消费端冒泡并触发 Kafka 重试。
     *
     * @param authorId 帖子作者 ID
     * @param postId 已发布知文 ID，用于构造稳定 businessRef
     * @return 实际发放积分；奖励关闭时返回 0
     */
    @Transactional
    public long rewardPostCreationStrict(long authorId, long postId) {
        if (!properties.isEnabled()) {
            return 0L;
        }
        long amount = properties.getPostAmount();
        walletService.grant(
                authorId,
                amount,
                WalletLedgerReason.CONTENT_CREATION_REWARD,
                WalletBusinessType.CONTENT,
                "content-reward:post:" + postId
        );
        return amount;
    }

    /**
     * 评论创建成功后发放积分奖励。
     *
     * @param creatorId 评论创建者 user id。
     * @param commentId 评论 id（用于 businessRef 幂等）。
     * @return 实际发放积分；enabled=false 或失败返回 0。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long rewardCommentCreation(long creatorId, long commentId) {
        if (!properties.isEnabled()) {
            return 0L;
        }
        String businessRef = "content-reward:comment:" + commentId;
        return grantSafely(creatorId, properties.getCommentAmount(), businessRef, "comment", commentId);
    }

    /**
     * 调 grant 发放积分，catch 所有异常不冒泡。
     * <p>
     * grant 内置 businessRef 幂等：同 businessRef + 同操作重试幂等返回（不重复发），
     * 不同操作 reject 抛 WALLET_DUPLICATE_BUSINESS_REF（理论不会发生，businessRef 含 postId/commentId 唯一）。
     */
    private long grantSafely(long userId, long amount, String businessRef, String contentType, long contentId) {
        try {
            walletService.grant(userId, amount, WalletLedgerReason.CONTENT_CREATION_REWARD,
                    WalletBusinessType.CONTENT, businessRef);
            return amount;
        } catch (Exception e) {
            log.warn("content reward grant failed: userId={} {}={} businessRef={} amount={} reason={}",
                    userId, contentType, contentId, businessRef, amount, e);
            return 0L;
        }
    }
}
