package com.tongji.moderation.service.impl;

import com.tongji.comment.mapper.CommentMapper;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.moderation.config.ModerationProperties;
import com.tongji.moderation.service.ModerationLlmClient;
import com.tongji.storage.text.TextStorageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "moderation.llm", name = "enabled", havingValue = "true")
public class ModerationLlmProviderConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "moderation.llm", name = "provider", havingValue = "dashscope", matchIfMissing = true)
    ModerationLlmClient dashScopeModerationLlmClient(
            @Qualifier("dashScopeChatModel") ChatModel chatModel,
            @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String modelName,
            ModerationProperties properties,
            KnowPostMapper knowPostMapper,
            CommentMapper commentMapper,
            TextStorageService textStorageService) {
        return pipeline(chatModel, "dashscope", modelName, properties, knowPostMapper, commentMapper, textStorageService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "moderation.llm", name = "provider", havingValue = "opencode")
    ModerationLlmClient openCodeModerationLlmClient(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model:deepseek-v4-flash-free}") String modelName,
            ModerationProperties properties,
            KnowPostMapper knowPostMapper,
            CommentMapper commentMapper,
            TextStorageService textStorageService) {
        return pipeline(chatModel, "opencode", modelName, properties, knowPostMapper, commentMapper, textStorageService);
    }

    private ModerationLlmClient pipeline(ChatModel chatModel,
                                         String provider,
                                         String modelName,
                                         ModerationProperties properties,
                                         KnowPostMapper knowPostMapper,
                                         CommentMapper commentMapper,
                                         TextStorageService textStorageService) {
        return new SpringAiModerationPipeline(
                ChatClient.builder(chatModel),
                provider,
                modelName,
                properties,
                knowPostMapper,
                commentMapper,
                textStorageService
        );
    }
}
