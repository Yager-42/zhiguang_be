package com.tongji.promotion.bprime.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.promotion.bprime.config.PromotionBPrimeProperties;
import com.tongji.promotion.bprime.model.PromotionAuctionCommand;
import com.tongji.promotion.bprime.service.PromotionCommandProcessingService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.consumer.rebalance.AllocateMessageQueueAveragely;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.support.RocketMQUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 RocketMQ 队列有序批量裁决命令，并在整批 Kafka durable ACK 后确认消费。
 *
 * <p>该组件直接使用 RocketMQ 原生批监听器，避免 Spring 单消息适配器在批内逐条等待远程 ACK。</p>
 *
 * @since 2026-08-09
 */
@Component
@ConditionalOnProperty(
        name = {"promotion.bprime.enabled", "promotion.bprime.decision-consumer-enabled"},
        havingValue = "true")
public class PromotionCommandRocketMqListener implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionCommandRocketMqListener.class);

    private final PromotionCommandProcessingService processingService;
    private final PromotionBPrimeProperties properties;
    private final RocketMQProperties rocketMqProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private volatile DefaultMQPushConsumer consumer;
    private volatile boolean running;

    public PromotionCommandRocketMqListener(
            PromotionCommandProcessingService processingService,
            PromotionBPrimeProperties properties,
            RocketMQProperties rocketMqProperties,
            Environment environment,
            ObjectMapper objectMapper) {
        this.processingService = processingService;
        this.properties = properties;
        this.rocketMqProperties = rocketMqProperties;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /** 启动受 Spring 生命周期管理的 RocketMQ 有序批消费者。 */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        DefaultMQPushConsumer newConsumer = createConsumer();
        try {
            newConsumer.start();
        } catch (Exception exception) {
            newConsumer.shutdown();
            throw new IllegalStateException("Failed to start promotion command batch consumer", exception);
        }
        consumer = newConsumer;
        running = true;
        LOGGER.info("Promotion command batch consumer started: group={}, topic={}, batchSize={}",
                properties.getCommandConsumerGroup(), properties.getCommandTopic(), properties.getDecisionBatchSize());
    }

    /** 停止消费者并释放 RocketMQ 网络与消费线程。 */
    @Override
    public synchronized void stop() {
        DefaultMQPushConsumer current = consumer;
        consumer = null;
        running = false;
        if (current != null) {
            current.shutdown();
        }
    }

    /** 返回消费者是否已成功启动。 */
    @Override
    public boolean isRunning() {
        return running;
    }

    ConsumeOrderlyStatus consumeMessages(List<MessageExt> messages, ConsumeOrderlyContext context) {
        try {
            List<PromotionAuctionCommand> commands = new ArrayList<>(messages.size());
            for (MessageExt message : messages) {
                commands.add(objectMapper.readValue(message.getBody(), PromotionAuctionCommand.class));
            }
            processingService.processBatch(commands);
            return ConsumeOrderlyStatus.SUCCESS;
        } catch (Exception exception) {
            context.setSuspendCurrentQueueTimeMillis(properties.getDecisionBatchSuspendMs());
            LOGGER.warn("Promotion command batch failed and will be retried: size={}, queue={}",
                    messages.size(), context.getMessageQueue(), exception);
            return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
        }
    }

    private DefaultMQPushConsumer createConsumer() {
        RocketMQProperties.PushConsumer pushProperties = rocketMqProperties.getConsumer();
        DefaultMQPushConsumer newConsumer = new DefaultMQPushConsumer(
                properties.getCommandConsumerGroup(),
                RocketMQUtil.getRPCHookByAkSk(
                        environment, pushProperties.getAccessKey(), pushProperties.getSecretKey()),
                new AllocateMessageQueueAveragely(),
                pushProperties.isEnableMsgTrace(),
                pushProperties.getCustomizedTraceTopic());
        newConsumer.setNamesrvAddr(rocketMqProperties.getNameServer());
        newConsumer.setNamespace(pushProperties.getNamespace());
        newConsumer.setUseTLS(pushProperties.isTlsEnable());
        if (StringUtils.hasText(pushProperties.getInstanceName())) {
            newConsumer.setInstanceName(pushProperties.getInstanceName());
        }
        int batchSize = properties.getDecisionBatchSize();
        int threadCount = properties.getDecisionConsumerThreadCount();
        newConsumer.setPullBatchSize(batchSize);
        newConsumer.setConsumeMessageBatchMaxSize(batchSize);
        newConsumer.setConsumeThreadMin(threadCount);
        newConsumer.setConsumeThreadMax(threadCount);
        newConsumer.setSuspendCurrentQueueTimeMillis(properties.getDecisionBatchSuspendMs());
        try {
            newConsumer.subscribe(properties.getCommandTopic(), "*");
        } catch (Exception exception) {
            newConsumer.shutdown();
            throw new IllegalStateException("Failed to subscribe promotion command topic", exception);
        }
        MessageListenerOrderly listener = this::consumeMessages;
        newConsumer.registerMessageListener(listener);
        return newConsumer;
    }
}
