package com.tradestore.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradestore.persistence.OutboxRepository;
import com.tradestore.service.OutboxRelayService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** The scheduled relay runs a batch on every poll and survives failures, so the next poll retries (CR-6). */
class OutboxRelaySchedulerTest {

    private final OutboxRelayService relay = mock(OutboxRelayService.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxRelayScheduler scheduler = new OutboxRelayScheduler(relay, outbox, registry, 3);

    @Test
    @Tag("US-101.3")
    void should_relayABatch_when_polled() {
        // Act
        scheduler.poll();

        // Assert
        verify(relay).relayBatch();
    }

    @Test
    @Tag("US-501.3")
    void should_notPropagateTheFailure_when_relayFails() {
        // Arrange
        when(relay.relayBatch()).thenThrow(new DataAccessResourceFailureException("MongoDB down"));

        // Act + Assert: the events stay in the outbox; the next poll retries them
        assertThatCode(scheduler::poll).doesNotThrowAnyException();
    }
}
