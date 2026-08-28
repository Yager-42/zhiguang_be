package com.tongji.knowpost.publish;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.manager.PublishAttemptService;
import com.tongji.storage.MinioStorageService;
import com.tongji.storage.text.TextDigestConflictException;
import com.tongji.storage.text.TextStorageService;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 执行一条发布请求的正文校验、幂等归档和 MySQL 完成事务。
 *
 * <p>实例无可变状态，由 Kafka Listener 在消费线程直接调用。</p>
 *
 * @since 2026-08-28
 */
@Service
public class PublishWorkflow {

    private final PublishAttemptService publishAttemptService;
    private final MinioStorageService minioStorageService;
    private final TextStorageService textStorageService;

    public PublishWorkflow(PublishAttemptService publishAttemptService,
                           MinioStorageService minioStorageService,
                           TextStorageService textStorageService) {
        this.publishAttemptService = publishAttemptService;
        this.minioStorageService = minioStorageService;
        this.textStorageService = textStorageService;
    }

    /**
     * 执行仍匹配当前 runVersion 的发布请求；终态和旧版本重放直接返回。
     *
     * @param event 受理事务冻结的发布快照
     * @throws PermanentPublishException 当对象缺失、摘要冲突或正文不是合法 UTF-8 时
     */
    public void execute(PublishRequestedEvent event) {
        if (!publishAttemptService.shouldProcess(event)) {
            return;
        }

        byte[] contentBytes;
        try {
            contentBytes = minioStorageService.readObjectBytes(event.contentObjectKey());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.BAD_REQUEST) {
                throw new PermanentPublishException(exception.getMessage(), exception);
            }
            throw exception;
        }

        String actualSha256 = sha256(contentBytes);
        if (!actualSha256.equalsIgnoreCase(event.contentSha256())) {
            throw new PermanentPublishException(
                    "正文摘要不匹配: expected=" + event.contentSha256() + ", actual=" + actualSha256);
        }

        String body = decodeUtf8(contentBytes);
        try {
            textStorageService.savePostTextIdempotent(event.postId(), body, actualSha256);
        } catch (TextDigestConflictException exception) {
            throw new PermanentPublishException(exception.getMessage(), exception);
        }
        publishAttemptService.completePublish(event);
    }

    private String sha256(byte[] contentBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contentBytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    private String decodeUtf8(byte[] contentBytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contentBytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new PermanentPublishException("正文不是合法 UTF-8", exception);
        }
    }
}
