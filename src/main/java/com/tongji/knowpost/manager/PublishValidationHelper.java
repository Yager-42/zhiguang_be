package com.tongji.knowpost.manager;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.knowpost.publish.PublishAttempt;
import org.springframework.stereotype.Component;

@Component
public class PublishValidationHelper {

    private final KnowPostMapper knowPostMapper;

    public PublishValidationHelper(KnowPostMapper knowPostMapper) {
        this.knowPostMapper = knowPostMapper;
    }

    public KnowPost loadOwnedPost(long authorId, long postId) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || post.getCreatorId() == null || post.getCreatorId() != authorId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "草稿不存在或无权限");
        }
        return post;
    }

    public void requirePublishableDraft(KnowPost post) {
        if (!"draft".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前状态不可发布");
        }
    }

    public void requireOwnedAttempt(PublishAttempt attempt, long authorId, long postId) {
        if (attempt == null
                || attempt.getCreatorId() == null
                || attempt.getPostId() == null
                || attempt.getCreatorId() != authorId
                || attempt.getPostId() != postId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布尝试不存在或无权限");
        }
    }

    public void requirePublishing(KnowPost post, PublishAttempt attempt) {
        if (!"publishing".equals(post.getStatus())
                || post.getPublishAttemptId() == null
                || !post.getPublishAttemptId().equals(attempt.getAttemptId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前不在发布中");
        }
    }

    public void requireRetryable(KnowPost post, PublishAttempt attempt) {
        if (!"failed".equals(attempt.getStatus()) || !"publish_failed".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布尝试不可重试");
        }
        if (post.getPublishAttemptId() == null || !post.getPublishAttemptId().equals(attempt.getAttemptId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布尝试与帖子状态不匹配");
        }
    }
}
