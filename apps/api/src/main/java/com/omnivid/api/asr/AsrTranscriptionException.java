package com.omnivid.api.asr;

public class AsrTranscriptionException extends RuntimeException {
    public AsrTranscriptionException(String message) {
        super(message);
    }

    public AsrTranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
