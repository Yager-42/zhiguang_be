package com.tongji.recommendation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxPayload;
import com.tongji.reconciliation.model.ReconciliationTargetType;
import com.tongji.reconciliation.model.ReconciliationTaskType;
import com.tongji.reconciliation.service.ReconciliationService;
import com.tongji.recommendation.gorse.GorseClient;
import com.tongji.recommendation.gorse.GorseItemInputFactory;
import com.tongji.recommendation.gorse.GorseProperties;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.outbox.OutboxTopics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ContentPublishedRecommendationConsumer {

    private final ObjectMapper objectMapper;
    private final GorseClient gorseClient;
    private final GorseProperties properties;
    private final TaskExecutor taskExecutor;
    private final ReconciliationService reconciliationService;
    private final KnowPostMapper knowPostMapper;
    private final GorseItemInputFactory itemInputFactory;

    public ContentPublishedRecommendationConsumer(ObjectMapper objectMapper,
                                                  GorseClient gorseClient,
                                                  GorseProperties properties,
                                                  @Qualifier("taskExecutor") TaskExecutor taskExecutor,
                                                  ReconciliationService reconciliationService,
                                                  KnowPostMapper knowPostMapper,
                                                  GorseItemInputFactory itemInputFactory) {
        this.objectMapper = objectMapper;
        this.gorseClient = gorseClient;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.reconciliationService = reconciliationService;
        this.knowPostMapper = knowPostMapper;
        this.itemInputFactory = itemInputFactory;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "recommendation-content-published-consumer")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        if (!properties.isEnabled()) {
            acknowledgment.acknowledge();
            return;
        }
        List<PublishedItem> items = parse(message);
        if (items.isEmpty()) {
            acknowledgment.acknowledge();
            return;
        }
        taskExecutor.execute(() -> {
            for (PublishedItem item : items) {
                try {
                    KnowPost post = knowPostMapper.findById(item.postId());
                    gorseClient.upsertItem(itemInputFactory.from(post));
                } catch (RuntimeException ignored) {
                    reconciliationService.createTaskIfAbsent(
                            ReconciliationTaskType.GORSE_ITEM_UPSERT,
                            ReconciliationTargetType.POST,
                            item.postId()
                    );
                }
            }
            acknowledgment.acknowledge();
        });
    }

    private List<PublishedItem> parse(String message) {
        List<PublishedItem> items = new ArrayList<>();
        for (OutboxEvent event : OutboxMessageReader.read(objectMapper, message)) {
            OutboxPayload payload = event.parsePayload(objectMapper).orElse(null);
            if (payload == null || !"content_published".equals(payload.text("eventType"))) {
                continue;
            }
            Long postId = payload.longValue("postId");
            Long authorId = payload.longValue("authorId");
            Instant publishedAt = payload.instantValue("publishedAt");
            if (postId != null && authorId != null && publishedAt != null) {
                items.add(new PublishedItem(postId, authorId, publishedAt));
            }
        }
        return items;
    }



    private record PublishedItem(long postId, long authorId, Instant publishedAt) {
    }
}
