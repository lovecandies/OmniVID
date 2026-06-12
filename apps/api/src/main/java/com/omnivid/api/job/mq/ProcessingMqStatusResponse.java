package com.omnivid.api.job.mq;

public record ProcessingMqStatusResponse(
        String mode,
        boolean connected,
        boolean publisherConnected,
        boolean consumerConnected,
        String namesrvAddr,
        String topic,
        long pendingEvents,
        long publishedEvents,
        long consumedEvents,
        long dlqEvents,
        String lastError
) {
}
