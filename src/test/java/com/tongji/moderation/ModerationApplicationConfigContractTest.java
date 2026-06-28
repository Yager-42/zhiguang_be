package com.tongji.moderation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationApplicationConfigContractTest {

    @Test
    void applicationYamlContainsDashScopeAndModerationControls() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));

        Properties properties = factory.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("spring.ai.dashscope.api-key"))
                .isEqualTo("${AI_DASHSCOPE_API_KEY:}");
        assertThat(properties.getProperty("spring.ai.dashscope.chat.options.model"))
                .isEqualTo("${AI_DASHSCOPE_MODEL:qwen-plus}");
        assertThat(properties.getProperty("moderation.llm.enabled"))
                .isEqualTo("${MODERATION_LLM_ENABLED:false}");
        assertThat(properties.getProperty("moderation.llm.min-confidence"))
                .isEqualTo("${MODERATION_LLM_MIN_CONFIDENCE:0.8000}");
        assertThat(properties.getProperty("moderation.llm.max-content-chars"))
                .isEqualTo("${MODERATION_LLM_MAX_CONTENT_CHARS:4000}");
        assertThat(properties.getProperty("moderation.llm.max-retries"))
                .isEqualTo("${MODERATION_LLM_MAX_RETRIES:3}");
        assertThat(properties.getProperty("moderation.notification.platform-actor-user-id"))
                .isEqualTo("${MODERATION_PLATFORM_ACTOR_USER_ID:0}");
    }
}
