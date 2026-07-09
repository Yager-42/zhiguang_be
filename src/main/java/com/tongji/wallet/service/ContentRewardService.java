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
 * <p>
 * 发帖 / 发评论成功后由各自挂载点调用，向创作者钱包发放可配置积分。
 * 复用 {@link WalletService#grant} 的 businessRef 内置幂等保证同帖 / 同评论只发一次。
 * <p>
 * 事务与失败语义（关键）：
 * <ul>
 *   <li>{@link Propagation#REQUIRES_NEW}：独立子事务，与调用方主事务隔离。</li>
 *   <li><b>catch 必须在本 service 方法体内</b>：grant 抛任意异常（含非受检如 RuntimeException / DataAccessException）
 *       均在此 catch 吞掉返回 0，<b>异常不冒泡 caller</b>。原因：REQUIRES_NEW 子事务回滚抛异常若被 caller
 *       的 @Transactional catch，外层仍可能被 Spring 标记 rollback-only（UnexpectedRollbackException 陷阱）。
 *       catch 在子事务方法体内，异常不进入外层事务边界，才能真正隔离。</li>
 *   <li>{@code enabled=false} 时 no-op 返回 0，不调 grant。</li>
 *   <li>失败时返回 0 + warn 日志（带 businessRef 便于排查），不阻塞发帖 / 评论主流程。</li>
 * </ul>
 * <p>
 * 返回值含义：实际发放积分（enabled=false 或失败返回 0）。调用方仅用于记日志 / 排查，
 * 不用于驱动前端提示（前端金额走 {@code GET /api/v1/content-reward/config}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentRewardService {

    private final WalletService walletService;
    private final ContentRewardProperties properties;

    /**
     * 发帖发布成功后发放积分奖励。
     *
     * @param authorId 帖子作者 user id。
     * @param postId   帖子 id（用于 businessRef 幂等）。
     * @return 实际发放积分；enabled=false 或失败返回 0。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long rewardPostCreation(long authorId, long postId) {
        if (!properties.isEnabled()) {
            return 0L;
        }
        String businessRef = "content-reward:post:" + postId;
        return grantSafely(authorId, properties.getPostAmount(), businessRef, "post", postId);
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
