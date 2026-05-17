package com.tongji.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
    private String endpoint;
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "us-east-1";
    private String publicDomain;
    private String folder = "avatars";

    public String publicUrl(String objectKey) {
        if (publicDomain != null && !publicDomain.isBlank()) {
            return trimTrailingSlash(publicDomain) + "/" + objectKey;
        }
        return trimTrailingSlash(publicEndpoint != null && !publicEndpoint.isBlank() ? publicEndpoint : endpoint)
                + "/" + bucket + "/" + objectKey;
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("/$", "");
    }
}
