package com.omnivid.api.job.mq;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProcessingMqStatusService {
    private final ProcessingEventRepository events;
    private final RocketMqConnectionState state;
    private final String mode;
    private final String namesrvAddr;
    private final String topic;

    public ProcessingMqStatusService(
            ProcessingEventRepository events,
            RocketMqConnectionState state,
            @Value("${omnivid.processing.mode:local}") String mode,
            @Value("${omnivid.processing.rocketmq.namesrv-addr:localhost:9876}") String namesrvAddr,
            @Value("${omnivid.processing.rocketmq.topic:omnivid-processing}") String topic
    ) {
        this.events = events;
        this.state = state;
        this.mode = mode;
        this.namesrvAddr = namesrvAddr;
        this.topic = topic;
    }

    public ProcessingMqStatusResponse status() {
        boolean local = "local".equalsIgnoreCase(mode);
        return new ProcessingMqStatusResponse(
                mode,
                local || state.connected(),
                local || state.publisherAvailable(),
                local || state.consumerAvailable(),
                namesrvAddr,
                topic,
                events.countByStatus("PENDING") + events.countByStatus("PUBLISH_FAILED"),
                events.countByStatus("PUBLISHED")
                        + events.countByStatus("CONSUMING")
                        + events.countByStatus("CONSUME_FAILED"),
                events.countByStatus("CONSUMED"),
                events.countByStatus("DLQ"),
                state.lastError()
        );
    }
}
