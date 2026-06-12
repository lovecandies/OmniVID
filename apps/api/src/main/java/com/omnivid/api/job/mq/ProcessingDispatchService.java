package com.omnivid.api.job.mq;

public interface ProcessingDispatchService {
    void dispatch(ProcessingCommand command);
}
