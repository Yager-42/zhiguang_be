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

    /**
     * 校验草稿已经确认可冻结的正文对象与摘要。
     *
     * @param post 待受理发布的草稿
     * @throws BusinessException 当正文对象或 SHA-256 缺失、格式非法时
     */
    public void requireContentSnapshot(KnowPost post) {
        String objectKey = post.getContentObjectKey();
        String sha256 = post.getContentSha256();
        if (objectKey == null || objectKey.isBlank()
                || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "正文尚未确认，无法发布");
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
        if (post == null || !"failed".equals(attempt.getStatus()) || !"publish_failed".equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前发布尝试不可重试");
        }
        if (post.getPublishAttemptId() == null || !post.getPublishAttemptId().equals(attempt.getAttemptId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "发布尝试与帖子状态不匹配");
        }
    }
}
