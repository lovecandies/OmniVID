package com.omnivid.api.job.mq;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.omnivid.api.observability.TraceContext;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "omnivid.processing", name = "mode", havingValue = "rocketmq")
public class RocketMqOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(RocketMqOutboxPublisher.class);

    private final ProcessingEventRepository events;
    private final RocketMqConnectionState state;
    private final String namesrvAddr;
    private final String topic;
    private final String producerGroup;
    private DefaultMQProducer producer;

    public RocketMqOutboxPublisher(
            ProcessingEventRepository events,
            RocketMqConnectionState state,
            @Value("${omnivid.processing.rocketmq.namesrv-addr}") String namesrvAddr,
            @Value("${omnivid.processing.rocketmq.topic}") String topic,
            @Value("${omnivid.processing.rocketmq.producer-group}") String producerGroup
    ) {
        this.events = events;
        this.state = state;
        this.namesrvAddr = namesrvAddr;
        this.topic = topic;
        this.producerGroup = producerGroup;
    }

    @Scheduled(fixedDelayString = "${omnivid.processing.rocketmq.publish-interval-ms:1000}")
    public synchronized void publishPending() {
        if (!ensureProducer()) {
            return;
        }
        for (ProcessingEvent event : events.listDispatchable(20)) {
            ProcessingCommand command;
            try {
                command = events.command(event);
            } catch (RuntimeException exception) {
                try (TraceContext.Scope ignored = TraceContext.open(null, Map.of(
                        "eventId", event.eventId(),
                        "jobId", event.jobId(),
                        "videoId", event.videoId()
                ))) {
                    events.markConsumeFailed(event.eventId(), exception.getMessage(), true);
                    log.warn("rocketmq_event_invalid_payload_dlq");
                }
                continue;
            }
            try (TraceContext.Scope ignored = TraceContext.open(command.traceId(), Map.of(
                    "eventId", event.eventId(),
                    "jobId", event.jobId(),
                    "videoId", event.videoId()
            ))) {
                Message message = new Message(
                        topic,
                        ProcessingEventRepository.EVENT_TYPE,
                        event.eventId(),
                        event.eventId().getBytes(StandardCharsets.UTF_8)
                );
                message.putUserProperty(TraceContext.TRACE_ID, command.traceId());
                if (producer.send(message).getSendStatus() == SendStatus.SEND_OK) {
                    events.markPublished(event.eventId());
                    state.publisherConnected();
                    log.info("rocketmq_event_published");
                } else {
                    events.markPublishFailed(event.eventId(), "RocketMQ send status was not SEND_OK");
                    log.warn("rocketmq_event_publish_failed");
                }
            } catch (Exception exception) {
                events.markPublishFailed(event.eventId(), exception.getMessage());
                state.publisherDisconnected(exception.getMessage());
                log.warn("rocketmq_event_publish_failed");
                shutdownProducer();
                return;
            }
        }
    }

    private boolean ensureProducer() {
        if (producer != null) {
            return true;
        }
        try {
            DefaultMQProducer candidate = new DefaultMQProducer(producerGroup);
            candidate.setNamesrvAddr(namesrvAddr);
            candidate.setVipChannelEnabled(false);
            candidate.setSendMsgTimeout(5000);
            candidate.start();
            producer = candidate;
            state.publisherConnected();
            return true;
        } catch (Exception exception) {
            state.publisherDisconnected(exception.getMessage());
            shutdownProducer();
            return false;
        }
    }

    @PreDestroy
    public synchronized void shutdownProducer() {
        if (producer != null) {
            producer.shutdown();
            producer = null;
        }
    }
}
