package com.tongji.recommendation.feed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.outbox.OutboxEvent;
import com.tongji.outbox.OutboxMessageReader;
import com.tongji.outbox.OutboxTopics;
import com.tongji.relation.event.RelationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * 关注后历史补偿消费者。
 *
 * <p>职责：消费 FollowCreated/FollowCanceled 事件，保证关注流在“关注了一个已有知文的作者”
 * 后立即能看到该作者的历史内容：</p>
 * <ul>
 *   <li>FollowCreated：把作者近期已发布的知文回填到作者时间线（feed_author_feed），
 *       并失效该作者的头部缓存与关注者的 timeline 缓存。回填为幂等 upsert，
 *       重复投递/重放安全，不依赖去重键。</li>
 *   <li>FollowCanceled：仅失效关注者的 timeline 缓存，避免最长 5 分钟内仍展示已取关作者的内容。</li>
 * </ul>
 *
 * <p>任一事件处理失败时不 ack，交由 Kafka 重投；效果全部幂等，重放收敛到同一结果。</p>
 */
@Service
public class FeedFollowCompensationConsumer {
    private static final Logger log = LoggerFactory.getLogger(FeedFollowCompensationConsumer.class);
    private static final int BACKFILL_LIMIT = 100;

    private final ObjectMapper objectMapper;
    private final TimelineExecutor timelineExecutor;
    private final FollowFeedService followFeedService;

    public FeedFollowCompensationConsumer(ObjectMapper objectMapper,
                                          TimelineExecutor timelineExecutor,
                                          FollowFeedService followFeedService) {
        this.objectMapper = objectMapper;
        this.timelineExecutor = timelineExecutor;
        this.followFeedService = followFeedService;
    }

    @KafkaListener(topics = OutboxTopics.CANAL_OUTBOX, groupId = "feed-follow-compensation")
    public void onMessage(String message, Acknowledgment acknowledgment) {
        boolean failed = false;
        for (OutboxEvent envelope : OutboxMessageReader.read(objectMapper, message)) {
            RelationEvent event = envelope.payloadAs(objectMapper, RelationEvent.class).orElse(null);
            if (event == null || event.fromUserId() == null || event.toUserId() == null) {
                continue;
            }
            try {
                if ("FollowCreated".equals(event.type())) {
                    // 历史补偿：作者时间线回填（幂等），并清掉可能已存在的陈旧缓存
                    timelineExecutor.backfillAuthorTimeline(event.toUserId(), BACKFILL_LIMIT);
                    followFeedService.invalidateAuthorHeadCache(event.toUserId());
                    followFeedService.invalidateTimelineCache(event.fromUserId());
                } else if ("FollowCanceled".equals(event.type())) {
                    followFeedService.invalidateTimelineCache(event.fromUserId());
                }
            } catch (Throwable throwable) {
                failed = true;
                log.warn("follow feed compensation failed for event type={} from={} to={}",
                        event.type(), event.fromUserId(), event.toUserId(), throwable);
            }
        }
        if (!failed) {
            acknowledgment.acknowledge();
        }
    }
}
