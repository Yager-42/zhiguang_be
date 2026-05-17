package com.tongji.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class)
            .withBean("openAiChatModel", ChatModel.class, () -> mock(ChatModel.class));

    @Test
    void chatClientUsesOpenAiCompatibleChatModel() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ChatClient.class));
    }
}
