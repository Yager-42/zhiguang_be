package com.tongji.moderation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationLlmOpenAiCompatibleIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "llm.integration", matches = "true")
    void opencodeChatCompletionsEndpointReturnsOpenAiCompatibleResponse() throws Exception {
        Properties properties = applicationProperties();
        String baseUrl = placeholderDefault(properties.getProperty("spring.ai.openai.base-url"));
        String apiKey = placeholderDefault(properties.getProperty("spring.ai.openai.api-key"));
        String model = placeholderDefault(properties.getProperty("spring.ai.openai.chat.options.model"));
        String endpoint = baseUrl + OpenAiChatProperties.DEFAULT_COMPLETIONS_PATH;

        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "user", "content", "Say OK.")
                ),
                "temperature", 0,
                "max_tokens", 128
        );

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("LLM response body: %s", response.body())
                .isBetween(200, 299);

        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.path("object").asText()).isEqualTo("chat.completion");
        assertThat(root.path("model").asText()).isEqualTo(model);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        assertThat(content.isTextual()).isTrue();
        assertThat(content.asText()).isNotBlank();
    }

    private Properties applicationProperties() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        Properties properties = factory.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    private String placeholderDefault(String value) {
        assertThat(value).startsWith("${").endsWith("}");
        int separator = value.indexOf(':');
        assertThat(separator).isGreaterThan(0);
        return value.substring(separator + 1, value.length() - 1);
    }
}
