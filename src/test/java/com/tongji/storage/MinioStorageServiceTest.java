package com.tongji.storage;

import com.tongji.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinioStorageServiceTest {

    @Test
    void publicUrlUsesMinioPathStyleEndpointByDefault() {
        StorageProperties properties = new StorageProperties();
        properties.setPublicEndpoint("http://localhost:9000");
        properties.setBucket("zhiguang");

        assertThat(properties.publicUrl("posts/1/content.md"))
                .isEqualTo("http://localhost:9000/zhiguang/posts/1/content.md");
    }

    @Test
    void publicUrlCanUseConfiguredPublicDomain() {
        StorageProperties properties = new StorageProperties();
        properties.setPublicDomain("https://static.example.com/assets/");

        assertThat(properties.publicUrl("avatars/1.png"))
                .isEqualTo("https://static.example.com/assets/avatars/1.png");
    }

    @Test
    void presignedPutUrlTargetsConfiguredMinioEndpoint() {
        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://localhost:9000");
        properties.setAccessKey("minioadmin");
        properties.setSecretKey("minioadmin");
        properties.setBucket("zhiguang");

        MinioStorageService service = new MinioStorageService(properties);

        String url = service.generatePresignedPutUrl("posts/1/content.md", "text/markdown", 600);

        assertThat(url).startsWith("http://localhost:9000/zhiguang/posts/1/content.md?");
        assertThat(url).contains("X-Amz-Signature=");
    }
}
