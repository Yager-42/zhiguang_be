package com.tongji.moderation;

import com.tongji.comment.mapper.CommentMapper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.moderation.config.ModerationProperties;
import com.tongji.moderation.service.ModerationLlmClient;
import com.tongji.moderation.service.impl.DisabledModerationLlmClient;
import com.tongji.moderation.service.impl.ModerationLlmProviderConfiguration;
import com.tongji.storage.text.TextStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModerationLlmProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ModerationProperties.class)
            .withBean(KnowPostMapper.class, () -> mock(KnowPostMapper.class))
            .withBean(CommentMapper.class, () -> mock(CommentMapper.class))
            .withBean(TextStorageService.class, () -> mock(TextStorageService.class))
            .withBean("dashScopeChatModel", ChatModel.class, () -> mock(ChatModel.class))
            .withBean("openAiChatModel", ChatModel.class, () -> mock(ChatModel.class))
            .withUserConfiguration(ModerationLlmProviderConfiguration.class, DisabledModerationLlmClient.class);

    @Test
    void disabledModerationProvidesOnlyDisabledClient() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModerationLlmClient.class);
            assertThat(context.getBean(ModerationLlmClient.class)).isInstanceOf(DisabledModerationLlmClient.class);
        });
    }

    @Test
    void enabledModerationDefaultsToDashScopeAdapter() {
        contextRunner
                .withPropertyValues("moderation.llm.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModerationLlmClient.class);
                    assertThat(context).hasBean("dashScopeModerationLlmClient");
                    assertThat(context).doesNotHaveBean("openCodeModerationLlmClient");
                });
    }

    @Test
    void enabledOpenCodeSelectsOnlyOpenAiAdapter() {
        contextRunner
                .withPropertyValues(
                        "moderation.llm.enabled=true",
                        "moderation.llm.provider=opencode"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ModerationLlmClient.class);
                    assertThat(context).hasBean("openCodeModerationLlmClient");
                    assertThat(context).doesNotHaveBean("dashScopeModerationLlmClient");
                });
    }

    @Test
    void unsupportedProviderFailsClosedWithoutAClient() {
        contextRunner
                .withPropertyValues(
                        "moderation.llm.enabled=true",
                        "moderation.llm.provider=unknown"
                )
                .run(context -> assertThat(context).doesNotHaveBean(ModerationLlmClient.class));
    }
}
