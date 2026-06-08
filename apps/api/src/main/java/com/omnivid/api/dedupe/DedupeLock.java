package com.omnivid.api.dedupe;

public interface DedupeLock extends AutoCloseable {
    boolean acquired();

    @Override
    void close();
}
