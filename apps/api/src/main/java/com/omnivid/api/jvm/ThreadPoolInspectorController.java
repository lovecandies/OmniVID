package com.omnivid.api.jvm;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jvm")
public class ThreadPoolInspectorController {
    private final ThreadPoolTaskExecutor executor;

    public ThreadPoolInspectorController(ThreadPoolTaskExecutor omnividProcessingExecutor) {
        this.executor = omnividProcessingExecutor;
    }

    @GetMapping("/thread-pool")
    ThreadPoolInspectorResponse inspect() {
        ThreadPoolExecutor nativeExecutor = executor.getThreadPoolExecutor();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        return new ThreadPoolInspectorResponse(
                "omnividProcessingExecutor",
                executor.getThreadNamePrefix(),
                executor.getCorePoolSize(),
                executor.getMaxPoolSize(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                nativeExecutor.getQueue().size(),
                nativeExecutor.getQueue().remainingCapacity(),
                nativeExecutor.getCompletedTaskCount(),
                nativeExecutor.getTaskCount(),
                nativeExecutor.getRejectedExecutionHandler().getClass().getSimpleName(),
                memory.getHeapMemoryUsage().getUsed(),
                memory.getHeapMemoryUsage().getMax(),
                memory.getNonHeapMemoryUsage().getUsed(),
                Runtime.getRuntime().availableProcessors()
        );
    }
}
