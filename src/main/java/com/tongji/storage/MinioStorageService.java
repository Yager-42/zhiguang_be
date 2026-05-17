package com.tongji.storage;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.storage.config.StorageProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MinioStorageService {

    private final StorageProperties props;

    public String uploadAvatar(long userId, MultipartFile file) {
        ensureConfigured();

        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.'));
        }
        String objectKey = props.getFolder() + "/" + userId + "-" + Instant.now().toEpochMilli() + ext;

        try {
            ensureBucket();
            client().putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            return props.publicUrl(objectKey);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "对象存储上传失败: " + e.getMessage());
        }
    }

    public String generatePresignedPutUrl(String objectKey, String contentType, int expiresInSeconds) {
        ensureConfigured();

        try {
            return client().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(props.getBucket())
                    .region(props.getRegion())
                    .object(objectKey)
                    .expiry(expiresInSeconds, TimeUnit.SECONDS)
                    .extraHeaders(contentType == null || contentType.isBlank()
                            ? Map.of()
                            : Map.of("Content-Type", contentType))
                    .build());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "对象存储签名失败: " + e.getMessage());
        }
    }

    public String publicUrl(String objectKey) {
        return props.publicUrl(objectKey);
    }

    private MinioClient client() {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    private void ensureBucket() throws Exception {
        try {
            client().makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
        } catch (ErrorResponseException e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.errorResponse().code())
                    && !"BucketAlreadyExists".equals(e.errorResponse().code())) {
                throw e;
            }
        }
    }

    private void ensureConfigured() {
        if (isBlank(props.getEndpoint()) || isBlank(props.getAccessKey())
                || isBlank(props.getSecretKey()) || isBlank(props.getBucket())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储未配置");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
