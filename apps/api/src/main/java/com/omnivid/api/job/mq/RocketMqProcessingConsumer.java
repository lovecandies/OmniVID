package com.omnivid.api.job.mq;

import com.omnivid.api.video.VideoService;
import com.omnivid.api.observability.TraceContext;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "omnivid.processing", name = "mode", havingValue = "rocketmq")
public class RocketMqProcessingConsumer {
    private static final Logger log = LoggerFactory.getLogger(RocketMqProcessingConsumer.class);

    private final ProcessingEventRepository events;
    private final VideoService videos;
    private final RocketMqConnectionState state;
    private final String namesrvAddr;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private DefaultMQPushConsumer consumer;

    public RocketMqProcessingConsumer(
            ProcessingEventRepository events,
            VideoService videos,
            RocketMqConnectionState state,
            @Value("${omnivid.processing.rocketmq.namesrv-addr}") String namesrvAddr,
            @Value("${omnivid.processing.rocketmq.topic}") String topic,
            @Value("${omnivid.processing.rocketmq.consumer-group}") String consumerGroup,
            @Value("${omnivid.processing.rocketmq.max-reconsume-times:3}") int maxReconsumeTimes
    ) {
        this.events = events;
        this.videos = videos;
        this.state = state;
        this.namesrvAddr = namesrvAddr;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.maxReconsumeTimes = Math.max(1, maxReconsumeTimes);
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void ensureConsumer() {
        if (consumer != null) {
            return;
        }
        try {
            DefaultMQPushConsumer candidate = new DefaultMQPushConsumer(consumerGroup);
            candidate.setNamesrvAddr(namesrvAddr);
            candidate.setVipChannelEnabled(false);
            candidate.setConsumeThreadMin(1);
            candidate.setConsumeThreadMax(2);
            candidate.setMaxReconsumeTimes(maxReconsumeTimes);
            candidate.subscribe(topic, ProcessingEventRepository.EVENT_TYPE);
            candidate.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (var message : messages) {
                    String eventId = new String(message.getBody(), StandardCharsets.UTF_8).trim();
                    ProcessingEvent event = events.findById(eventId).orElse(null);
                    if (event == null || events.isConsumed(eventId) || "DLQ".equals(event.status())) {
                        continue;
                    }
                    ProcessingCommand command = events.command(event);
                    String traceId = command.traceId();
                    if (!events.tryMarkConsuming(eventId)) {
                        ProcessingEvent current = events.findById(eventId).orElse(null);
                        if (current == null || "CONSUMED".equals(current.status()) || "DLQ".equals(current.status())) {
                            continue;
                        }
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                    try (TraceContext.Scope ignored = TraceContext.open(traceId, Map.of(
                            "eventId", eventId,
                            "jobId", event.jobId(),
                            "videoId", event.videoId()
                    ))) {
                        try {
                            log.info("rocketmq_event_consuming");
                            boolean completed = videos.runProcessingCommand(command);
                            if (completed) {
                                events.markConsumed(eventId);
                                log.info("rocketmq_event_consumed");
                                continue;
                            }
                            boolean dlq = message.getReconsumeTimes() + 1 >= maxReconsumeTimes;
                            events.markConsumeFailed(eventId, "Video processing DAG failed", dlq);
                            log.warn(dlq ? "rocketmq_event_dlq" : "rocketmq_event_consume_failed");
                            return dlq ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS : ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        } catch (RuntimeException exception) {
                            boolean dlq = message.getReconsumeTimes() + 1 >= maxReconsumeTimes;
                            events.markConsumeFailed(eventId, exception.getMessage(), dlq);
                            log.warn(dlq ? "rocketmq_event_dlq" : "rocketmq_event_consume_failed");
                            return dlq ? ConsumeConcurrentlyStatus.CONSUME_SUCCESS : ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            events.recoverInterruptedConsumers();
            candidate.start();
            consumer = candidate;
            state.consumerConnected();
        } catch (Exception exception) {
            state.consumerDisconnected(exception.getMessage());
            shutdownConsumer();
        }
    }

    @PreDestroy
    public synchronized void shutdownConsumer() {
        if (consumer != null) {
            consumer.shutdown();
            consumer = null;
        }
    }
}
