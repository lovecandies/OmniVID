package com.omnivid.api.job.mq;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ProcessingEventRepositoryTests {
    @Autowired
    private ProcessingEventRepository events;

    @Test
    void createsOneEventPerJobAndClaimsItOnce() {
        ProcessingCommand command = new ProcessingCommand(701, 901, false);

        ProcessingEvent created = events.create(command);
        ProcessingEvent duplicate = events.create(command);

        assertThat(duplicate.eventId()).isEqualTo(created.eventId());
        assertThat(events.tryMarkConsuming(created.eventId())).isTrue();
        assertThat(events.tryMarkConsuming(created.eventId())).isFalse();

        events.markPublished(created.eventId());
        assertThat(events.findById(created.eventId()).orElseThrow().status()).isEqualTo("CONSUMING");

        events.markConsumed(created.eventId());
        assertThat(events.findById(created.eventId()).orElseThrow().status()).isEqualTo("CONSUMED");
    }

    @Test
    void recoversInterruptedConsumerForRedelivery() {
        ProcessingEvent created = events.create(new ProcessingCommand(702, 902, true));
        assertThat(events.tryMarkConsuming(created.eventId())).isTrue();

        assertThat(events.recoverInterruptedConsumers()).isGreaterThanOrEqualTo(1);
        assertThat(events.findById(created.eventId()).orElseThrow().status()).isEqualTo("PUBLISHED");
    }
}
