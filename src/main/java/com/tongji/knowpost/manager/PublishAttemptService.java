package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.common.id.IdNamespace;
import com.tongji.common.id.IdService;
import com.tongji.knowpost.api.dto.PublishStatusResponse;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.ContentPublishedEvent;
import com.tongji.knowpost.publish.PermanentPublishException;
import com.tongji.knowpost.publish.PublishAttempt;
import com.tongji.knowpost.publish.PublishAttemptMapper;
import com.tongji.knowpost.publish.PublishOutboxWriter;
import com.tongji.knowpost.publish.PublishRequestedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 持有发布受理、手工重试和 MySQL 版本状态机的事务边界。
 *
 * <p>正文归档由 Kafka 消费工作流完成；本服务不根据时间推断执行任务是否死亡。</p>
 *
 * @since 2026-08-28
 */
@Service
public class PublishAttemptService {

    private static final int INITIAL_RUN_VERSION = 1;
    private static final int MAX_FAILED_STEP_LENGTH = 64;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1024;

    private final KnowPostMapper knowPostMapper;
    private final PublishAttemptMapper publishAttemptMapper;
    private final PublishValidationHelper publishValidationHelper;
    private final IdService idService;
    private final PublishOutboxWriter publishOutboxWriter;
    private final Clock clock;

    @Autowired
    public PublishAttemptService(KnowPostMapper knowPostMapper,
                                 PublishAttemptMapper publishAttemptMapper,
                                 PublishValidationHelper publishValidationHelper,
                                 IdService idService,
                                 PublishOutboxWriter publishOutboxWriter) {
        this(knowPostMapper, publishAttemptMapper, publishValidationHelper, idService,
                publishOutboxWriter, Clock.systemUTC());
    }

    PublishAttemptService(KnowPostMapper knowPostMapper,
                          PublishAttemptMapper publishAttemptMapper,
                          PublishValidationHelper publishValidationHelper,
                          IdService idService,
                          PublishOutboxWriter publishOutboxWriter,
                          Clock clock) {
        this.knowPostMapper = knowPostMapper;
        this.publishAttemptMapper = publishAttemptMapper;
        this.publishValidationHelper = publishValidationHelper;
        this.idService = idService;
        this.publishOutboxWriter = publishOutboxWriter;
        this.clock = clock;
    }

    /**
     * 原子受理一次发布请求并持久化对应的执行事件。
     *
     * @param authorId 已认证作者 ID
     * @param postId 作者草稿 ID
     * @param idempotentKey 同一作者和草稿范围内的客户端幂等键
     * @return 新建或已存在的发布尝试
     * @throws BusinessException 当草稿、正文快照或状态不允许发布时
     */
    @Transactional
    public PublishAttempt acceptPublish(long authorId, long postId, String idempotentKey) {
        if (idempotentKey == null || idempotentKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "幂等键不能为空");
        }
        PublishAttempt existing = publishAttemptMapper.findByIdempotencyKey(authorId, postId, idempotentKey);
        if (existing != null) {
            return existing;
        }

        KnowPost post = publishValidationHelper.loadOwnedPost(authorId, postId);
        publishValidationHelper.requirePublishableDraft(post);
        publishValidationHelper.requireContentSnapshot(post);

        Instant now = Instant.now(clock);
        PublishAttempt attempt = PublishAttempt.builder()
                .attemptId(idService.nextId(IdNamespace.PUBLISH_ATTEMPT))
                .postId(postId)
                .creatorId(authorId)
                .idempotentKey(idempotentKey)
                .status("publishing")
                .runVersion(INITIAL_RUN_VERSION)
                .contentObjectKeySnapshot(post.getContentObjectKey())
                .contentEtagSnapshot(normalizeNullable(post.getContentEtag()))
                .contentSha256Snapshot(post.getContentSha256().toLowerCase(Locale.ROOT))
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            publishAttemptMapper.insert(attempt);
        } catch (DuplicateKeyException duplicateKeyException) {
            PublishAttempt racedAttempt = publishAttemptMapper.findByIdempotencyKey(
                    authorId, postId, idempotentKey);
            if (racedAttempt != null) {
                return racedAttempt;
            }
            throw duplicateKeyException;
        }
        if (knowPostMapper.startPublishing(postId, authorId, attempt.getAttemptId()) != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前状态不可发布");
        }
        publishOutboxWriter.writeRequested(attempt);
        return attempt;
    }

    /**
     * 将失败 attempt 推进到下一执行轮次，并原子写入新的发布请求事件。
     *
     * @param authorId 已认证作者 ID
     * @param postId 知文 ID
     * @param attemptId 需要重试的发布尝试 ID
     * @return 已推进版本的发布尝试
     */
    @Transactional
    public PublishAttempt retryPublish(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptMapper.findById(attemptId);
        publishValidationHelper.requireOwnedAttempt(attempt, authorId, postId);
        KnowPost post = knowPostMapper.findPublishStatus(postId, authorId);
        publishValidationHelper.requireRetryable(post, attempt);

        int expectedRunVersion = runVersion(attempt);
        if (publishAttemptMapper.restartFailedAttempt(attemptId, expectedRunVersion) != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布尝试不可重试");
        }
        if (knowPostMapper.retryPublishing(postId, authorId, attemptId) != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布尝试不可重试");
        }

        attempt.setStatus("publishing");
        attempt.setRunVersion(expectedRunVersion + 1);
        attempt.setRetryCount(value(attempt.getRetryCount()) + 1);
        attempt.setFailedStep(null);
        attempt.setErrorMessage(null);
        attempt.setUpdatedAt(Instant.now(clock));
        publishOutboxWriter.writeRequested(attempt);
        return attempt;
    }

    /**
     * 查询发布状态；该读操作不会触发状态恢复或时间判死。
     *
     * @param authorId 已认证作者 ID
     * @param postId 知文 ID
     * @param attemptId 发布尝试 ID
     * @return 当前 attempt 与帖子状态
     */
    @Transactional(readOnly = true)
    public PublishStatusResponse getPublishStatus(long authorId, long postId, long attemptId) {
        PublishAttempt attempt = publishAttemptMapper.findById(attemptId);
        publishValidationHelper.requireOwnedAttempt(attempt, authorId, postId);
        KnowPost post = knowPostMapper.findPublishStatus(postId, authorId);
        if (post == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知文不存在或无权限");
        }
        return new PublishStatusResponse(
                String.valueOf(attempt.getAttemptId()),
                attempt.getStatus(),
                post.getStatus(),
                attempt.getFailedStep(),
                isRetryable(attempt, post)
        );
    }

    /**
     * 判断发布消息是否仍对应当前可执行轮次。
     *
     * @param event Kafka 中的受理快照
     * @return 当前轮次仍为 publishing 时返回 true；旧版本或终态重放返回 false
     * @throws PermanentPublishException 当消息身份或正文快照与 attempt 不一致时
     */
    @Transactional(readOnly = true)
    public boolean shouldProcess(PublishRequestedEvent event) {
        PublishAttempt attempt = publishAttemptMapper.findById(event.attemptId());
        if (attempt == null) {
            throw new PermanentPublishException("发布尝试不存在");
        }
        requireMatchingEvent(attempt, event);
        int currentRunVersion = runVersion(attempt);
        if (currentRunVersion > event.runVersion()) {
            return false;
        }
        if (currentRunVersion < event.runVersion()) {
            throw new IllegalStateException("发布事件版本领先于数据库状态");
        }
        if (isTerminal(attempt.getStatus())) {
            return false;
        }
        if (!"publishing".equals(attempt.getStatus())) {
            throw new PermanentPublishException("发布尝试状态非法: " + attempt.getStatus());
        }
        return true;
    }

    /**
     * 以 attempt/run CAS 完成发布，并在同一事务写入唯一发布事实。
     *
     * @param event 已完成正文归档的发布请求
     */
    @Transactional
    public void completePublish(PublishRequestedEvent event) {
        PublishAttempt attempt = requireMatchingAttempt(event);
        Instant publishedAt = Instant.now(clock);
        int affected = publishAttemptMapper.markSucceeded(
                event.attemptId(), event.runVersion(), publishedAt);
        if (affected != 1) {
            requireTerminalOrStale(event);
            return;
        }

        if (knowPostMapper.completePublish(
                event.postId(), event.authorId(), event.attemptId(), publishedAt) != 1) {
            throw new IllegalStateException("帖子发布完成状态与当前发布尝试不一致");
        }
        publishOutboxWriter.writeContentPublished(new ContentPublishedEvent(
                event.postId(), event.authorId(), attempt.getAttemptId(), event.runVersion(), publishedAt));
    }

    /**
     * 仅将仍匹配的执行轮次终止为失败。
     *
     * @param event 失败的发布请求
     * @param failedStep 失败阶段，最长 64 字符
     * @param errorMessage 可诊断错误信息，最长 1024 字符
     */
    @Transactional
    public void failPublish(PublishRequestedEvent event, String failedStep, String errorMessage) {
        requireMatchingAttempt(event);
        String normalizedMessage = truncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH, "发布失败");
        int affected = publishAttemptMapper.markFailed(
                event.attemptId(),
                event.runVersion(),
                truncate(failedStep, MAX_FAILED_STEP_LENGTH, "publish_failed"),
                normalizedMessage
        );
        if (affected != 1) {
            requireTerminalOrStale(event);
            return;
        }
        if (knowPostMapper.failPublish(
                event.postId(), event.authorId(), event.attemptId(), normalizedMessage) != 1) {
            throw new IllegalStateException("帖子发布失败状态与当前发布尝试不一致");
        }
    }

    private PublishAttempt requireMatchingAttempt(PublishRequestedEvent event) {
        PublishAttempt attempt = publishAttemptMapper.findById(event.attemptId());
        if (attempt == null) {
            throw new PermanentPublishException("发布尝试不存在");
        }
        requireMatchingEvent(attempt, event);
        return attempt;
    }

    private void requireMatchingEvent(PublishAttempt attempt, PublishRequestedEvent event) {
        if (!Objects.equals(attempt.getPostId(), event.postId())
                || !Objects.equals(attempt.getCreatorId(), event.authorId())
                || !Objects.equals(attempt.getContentObjectKeySnapshot(), event.contentObjectKey())
                || !Objects.equals(normalizeNullable(attempt.getContentEtagSnapshot()), event.contentEtag())
                || !equalHash(attempt.getContentSha256Snapshot(), event.contentSha256())) {
            throw new PermanentPublishException("发布事件与受理快照不一致");
        }
    }

    private void requireTerminalOrStale(PublishRequestedEvent event) {
        PublishAttempt current = publishAttemptMapper.findStatusById(event.attemptId());
        if (current == null) {
            throw new IllegalStateException("发布尝试不存在");
        }
        int currentRunVersion = runVersion(current);
        if (currentRunVersion > event.runVersion()) {
            return;
        }
        if (currentRunVersion == event.runVersion() && isTerminal(current.getStatus())) {
            return;
        }
        throw new IllegalStateException("发布状态 CAS 失败且不属于幂等终态");
    }

    private boolean isRetryable(PublishAttempt attempt, KnowPost post) {
        return "failed".equals(attempt.getStatus()) && "publish_failed".equals(post.getStatus());
    }

    private boolean isTerminal(String status) {
        return "succeeded".equals(status) || "failed".equals(status);
    }

    private int runVersion(PublishAttempt attempt) {
        return attempt.getRunVersion() == null ? INITIAL_RUN_VERSION : attempt.getRunVersion();
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private boolean equalHash(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int maxLength, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value;
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
