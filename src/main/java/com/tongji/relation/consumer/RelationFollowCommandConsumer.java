package com.tongji.relation.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.relation.command.FollowCommandEvent;
import com.tongji.relation.command.FollowCommandTopics;
import com.tongji.relation.manager.RelationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 关注命令消费端：执行关系事实写。
 *
 * <p>复用 {@link RelationManager} 的现有事务链路——幂等门控（affected==1 才计）、
 * AFTER_COMMIT 计数监听器、outbox 同事务——因此消息重投不会双写双计。
 * 处理失败不 ack，由容器 DefaultErrorHandler 指数退避重试后进 DLT。</p>
 */
@Component
public class RelationFollowCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(RelationFollowCommandConsumer.class);

    private final RelationManager relationManager;
    private final ObjectMapper objectMapper;

    public RelationFollowCommandConsumer(RelationManager relationManager, ObjectMapper objectMapper) {
        this.relationManager = relationManager;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = FollowCommandTopics.COMMAND,
            groupId = "${relation.kafka.command-group:relation-follow-command-consumer}",
            containerFactory = "relationCommandKafkaListenerContainerFactory"
    )
    public void onMessage(String message, Acknowledgment acknowledgment) throws Exception {
        FollowCommandEvent event = objectMapper.readValue(message, FollowCommandEvent.class);
        try {
            if (event.follow()) {
                relationManager.follow(event.fromUserId(), event.toUserId());
            } else {
                relationManager.unfollow(event.fromUserId(), event.toUserId());
            }
            acknowledgment.acknowledge();
        } catch (Exception ex) {
            // 不 ack：容器重试，耗尽后进 DLT（relation-command-dlt）
            log.error("follow command processing failed from={} to={} follow={}",
                    event.fromUserId(), event.toUserId(), event.follow(), ex);
            throw ex;
        }
    }
}