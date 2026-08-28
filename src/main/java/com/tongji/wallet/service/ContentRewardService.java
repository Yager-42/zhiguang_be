package com.tongji.wallet.service;

import com.tongji.wallet.config.ContentRewardProperties;
import com.tongji.wallet.model.WalletBusinessType;
import com.tongji.wallet.model.WalletLedgerReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容创作积分奖励发放服务。
 *
 * <p>发帖与评论奖励均由 Kafka 派生消费调用严格入口，异常必须向上冒泡以触发重试。
 * 两类奖励都依赖 {@link WalletService#grant} 的 businessRef 保证重复消费不重复入账。</p>
 */
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
     * 评论创建成功后发放积分奖励；钱包故障向消费端冒泡并触发 Kafka 重试。
     *
     * @param creatorId 评论创建者 user id
     * @param commentId 评论 id，用于构造稳定 businessRef
     * @return 实际发放积分；奖励关闭时返回 0
     */
    @Transactional
    public long rewardCommentCreationStrict(long creatorId, long commentId) {
        if (!properties.isEnabled()) {
            return 0L;
        }
        long amount = properties.getCommentAmount();
        walletService.grant(
                creatorId,
                amount,
                WalletLedgerReason.CONTENT_CREATION_REWARD,
                WalletBusinessType.CONTENT,
                "content-reward:comment:" + commentId
        );
        return amount;
    }
}
