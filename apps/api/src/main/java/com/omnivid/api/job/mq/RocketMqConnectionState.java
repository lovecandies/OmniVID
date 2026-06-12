package com.omnivid.api.job.mq;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RocketMqConnectionState {
    private final AtomicBoolean publisherConnected = new AtomicBoolean(false);
    private final AtomicBoolean consumerConnected = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    public void publisherConnected() {
        publisherConnected.set(true);
        clearErrorIfConnected();
    }

    public void consumerConnected() {
        consumerConnected.set(true);
        clearErrorIfConnected();
    }

    public void publisherDisconnected(String error) {
        publisherConnected.set(false);
        lastError.set(compact(error));
    }

    public void consumerDisconnected(String error) {
        consumerConnected.set(false);
        lastError.set(compact(error));
    }

    public boolean connected() {
        return publisherConnected.get() && consumerConnected.get();
    }

    public boolean publisherAvailable() {
        return publisherConnected.get();
    }

    public boolean consumerAvailable() {
        return consumerConnected.get();
    }

    public String lastError() {
        return lastError.get();
    }

    private void clearErrorIfConnected() {
        if (connected()) {
            lastError.set("");
        }
    }

    private String compact(String error) {
        if (error == null) {
            return "";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
