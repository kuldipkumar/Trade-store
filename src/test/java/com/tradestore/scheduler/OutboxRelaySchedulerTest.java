package com.tradestore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.tradestore.persistence.OutboxRepository;
import com.tradestore.service.OutboxRelayService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

    private double failedPolls() {
        var counter = registry.find("trade.job.failures").tag("job", "outbox-relay").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @Tag("US-501.3")
    void should_countEachFailedPoll_when_theRelayFails() {
        // Arrange
        when(relay.relayBatch()).thenThrow(new DataAccessResourceFailureException("MongoDB down"));

        // Act
        scheduler.poll();
        scheduler.poll();

        // Assert
        assertThat(failedPolls()).isEqualTo(2.0);
    }

    @Test
    @Tag("US-501.3")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logAnOutageAtErrorWithTheWaitingTrades_when_pollsKeepFailing(CapturedOutput output) {
        // Arrange: threshold 3
        when(relay.relayBatch()).thenThrow(new DataAccessResourceFailureException("MongoDB down"));
        when(outbox.pendingTradeIds(anyInt())).thenReturn(List.of("T1", "T2"));

        // Act + Assert: no ERROR yet after two failed polls
        scheduler.poll();
        scheduler.poll();
        assertThat(output).doesNotContain("ERROR");

        // Act + Assert: the third consecutive failure is an outage, named with the affected trades
        scheduler.poll();
        assertThat(output).contains("ERROR").contains("3 consecutive polls").contains("[T1, T2]");
    }

    @Test
    @Tag("US-501.3")
    @ExtendWith(OutputCaptureExtension.class)
    void should_startCountingAgain_when_aPollSucceedsInBetween(CapturedOutput output) {
        // Arrange: fail, fail, succeed, fail, fail (threshold 3)
        DataAccessResourceFailureException down = new DataAccessResourceFailureException("MongoDB down");
        when(relay.relayBatch()).thenThrow(down, down).thenReturn(5).thenThrow(down, down);

        // Act
        for (int i = 0; i < 5; i++) {
            scheduler.poll();
        }

        // Assert: never three in a row, so no outage
        assertThat(output).doesNotContain("ERROR");
    }

    @Test
    @Tag("US-501.3")
    @ExtendWith(OutputCaptureExtension.class)
    void should_repeatTheOutageErrorEveryThresholdPolls_when_theOutageContinues(CapturedOutput output) {
        // Arrange (threshold 3)
        when(relay.relayBatch()).thenThrow(new DataAccessResourceFailureException("MongoDB down"));

        // Act: six consecutive failures
        for (int i = 0; i < 6; i++) {
            scheduler.poll();
        }

        // Assert: reminded at 3 and 6, not on every poll
        assertThat(output.getAll().split("consecutive polls", -1).length - 1).isEqualTo(2);
    }
}
