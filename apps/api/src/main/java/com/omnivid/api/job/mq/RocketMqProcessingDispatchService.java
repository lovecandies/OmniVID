package com.omnivid.api.job.mq;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "omnivid.processing", name = "mode", havingValue = "rocketmq")
public class RocketMqProcessingDispatchService implements ProcessingDispatchService {
    private final ProcessingEventRepository events;

    public RocketMqProcessingDispatchService(ProcessingEventRepository events) {
        this.events = events;
    }

    @Override
    public void dispatch(ProcessingCommand command) {
        events.create(command);
    }
}
