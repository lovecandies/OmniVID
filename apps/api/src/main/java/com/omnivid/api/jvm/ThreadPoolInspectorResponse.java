package com.omnivid.api.jvm;

public record ThreadPoolInspectorResponse(
        String executorName,
        String threadNamePrefix,
        int corePoolSize,
        int maxPoolSize,
        int poolSize,
        int activeCount,
        int queueSize,
        int queueRemainingCapacity,
        long completedTaskCount,
        long taskCount,
        String rejectionPolicy,
        long heapUsedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        int availableProcessors
) {
}
