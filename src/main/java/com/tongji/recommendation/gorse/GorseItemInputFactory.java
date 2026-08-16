package com.tongji.recommendation.gorse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.knowpost.model.KnowPost;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class GorseItemInputFactory {

    private static final int MAX_TOPICS = 32;
    private static final int MAX_TOPIC_LENGTH = 64;
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public GorseItemInputFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将已发布知文转换为 Gorse 标签物料。
     */
    public GorseItemInput from(KnowPost post) {
        if (post == null || post.getId() == null || post.getCreatorId() == null || post.getPublishTime() == null) {
            throw new IllegalArgumentException("Published post metadata is incomplete for gorse item upsert");
        }
        return new GorseItemInput(
                post.getId(),
                post.getCreatorId(),
                post.getPublishTime(),
                post.getTitle(),
                parseTopics(post.getTags())
        );
    }

    private List<String> parseTopics(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> rawTopics = objectMapper.readValue(tagsJson, STRING_LIST_TYPE);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String topic : rawTopics) {
                if (topic == null) {
                    continue;
                }
                String value = topic.trim();
                if (!value.isEmpty()) {
                    normalized.add(value.substring(0, Math.min(value.length(), MAX_TOPIC_LENGTH)));
                }
                if (normalized.size() >= MAX_TOPICS) {
                    break;
                }
            }
            return new ArrayList<>(normalized);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
