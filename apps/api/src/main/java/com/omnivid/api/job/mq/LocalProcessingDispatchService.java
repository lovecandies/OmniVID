package com.omnivid.api.job.mq;

import com.omnivid.api.video.VideoService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(prefix = "omnivid.processing", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalProcessingDispatchService implements ProcessingDispatchService {
    private final ThreadPoolTaskExecutor executor;
    private final ObjectProvider<VideoService> videos;

    public LocalProcessingDispatchService(ThreadPoolTaskExecutor omnividProcessingExecutor, ObjectProvider<VideoService> videos) {
        this.executor = omnividProcessingExecutor;
        this.videos = videos;
    }

    @Override
    public void dispatch(ProcessingCommand command) {
        Runnable task = () -> videos.getObject().runProcessingCommand(command);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
            return;
        }
        executor.execute(task);
    }
}
