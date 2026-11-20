package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.domain.Violation;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeHistoryStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** ADR-005: outbox events are copied to the audit trail (and history), then deleted from the outbox. */
class OutboxRelayServiceTest {

    private static final Instant AT = Instant.parse("2026-11-19T10:15:30Z");
    private static final Trade T1 = new Trade("T1", 1, "CP-1", "B1", LocalDate.of(2027, 5, 20), LocalDate.of(2026, 11, 19), false);

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final AuditEventStore audit = mock(AuditEventStore.class);
    private final TradeHistoryStore history = mock(TradeHistoryStore.class);
    private final OutboxRelayService relay = new OutboxRelayService(outbox, audit, history, 100);

    private static AuditEvent event(Outcome outcome, Trade trade) {
        return new AuditEvent(UUID.randomUUID().toString(), "T1", 1, outcome,
                outcome == Outcome.REJECTED ? List.of(Violation.lowerVersion(1, 2)) : List.of(), Channel.REST, AT, "{}", trade);
    }

    @Test
    @Tag("US-101.3")
    @Tag("US-501.1")
    void should_writeAuditAndHistoryThenDelete_when_eventsAreClaimed() {
        // Arrange
        AuditEvent accepted = event(Outcome.ACCEPTED, T1);
        AuditEvent rejected = event(Outcome.REJECTED, null);
        when(outbox.claimBatch(100)).thenReturn(List.of(accepted, rejected));

        // Act
        int relayed = relay.relayBatch();

        // Assert
        assertThat(relayed).isEqualTo(2);
        verify(audit).insert(accepted);
        verify(audit).insert(rejected);
        verify(history).insert(TradeHistoryEntry.from(accepted));
        verify(history, org.mockito.Mockito.times(1)).insert(any(TradeHistoryEntry.class)); // not for REJECTED
        verify(outbox).delete(List.of(accepted.eventId(), rejected.eventId()));
    }

    @Test
    @Tag("US-102.3")
    void should_recordReplacedInHistory_when_eventIsReplaced() {
        // Arrange (US-102.3)
        AuditEvent replaced = event(Outcome.REPLACED, T1);
        when(outbox.claimBatch(100)).thenReturn(List.of(replaced));

        // Act
        relay.relayBatch();

        // Assert
        verify(history).insert(TradeHistoryEntry.from(replaced));
    }

    @Test
    @Tag("US-501.1")
    void should_doNothing_when_outboxIsEmpty() {
        // Arrange
        when(outbox.claimBatch(100)).thenReturn(List.of());

        // Act
        int relayed = relay.relayBatch();

        // Assert
        assertThat(relayed).isZero();
        verify(outbox, never()).delete(anyList());
    }

    @Test
    @Tag("US-501.3")
    void should_keepEventsInOutbox_when_mongoWriteFails() {
        // Arrange: the exception rolls back the claim, so the rows are retried on the next poll (CR-6)
        AuditEvent accepted = event(Outcome.ACCEPTED, T1);
        when(outbox.claimBatch(100)).thenReturn(List.of(accepted));
        doThrow(new DataAccessResourceFailureException("MongoDB down")).when(audit).insert(accepted);

        // Act + Assert
        assertThatThrownBy(relay::relayBatch).isInstanceOf(DataAccessResourceFailureException.class);
        verify(outbox, never()).delete(anyList());
    }
}
