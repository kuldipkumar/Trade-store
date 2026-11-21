package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Trade;
import com.tradestore.domain.Violation;
import com.tradestore.persistence.OutboxBacklog;
import com.tradestore.persistence.OutboxRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transactional outbox against real PostgreSQL (ADR-005). Each test runs in its own transaction and
 * rolls back, so uncommitted rows are invisible to the outbox relay running in the same application.
 */
@IntegrationTest
class JdbcOutboxRepositoryIT {

    private final OutboxRepository outbox;

    private final TransactionTemplate tx;

    JdbcOutboxRepositoryIT(OutboxRepository outbox, TransactionTemplate tx) {
        this.outbox = outbox;
        this.tx = tx;
    }

    private static AuditEvent accepted(String tradeId) {
        Trade trade = new Trade(tradeId, 1, "CP-1", "B1", LocalDate.of(2027, 5, 20), LocalDate.of(2026, 11, 19), false);
        return new AuditEvent(UUID.randomUUID().toString(), tradeId, 1, Outcome.ACCEPTED, List.of(), Channel.REST,
                Instant.parse("2026-11-19T10:15:30.123456Z"), "{\"tradeId\":\"" + tradeId + "\"}", trade);
    }

    private static AuditEvent rejected(String tradeId) {
        return new AuditEvent(UUID.randomUUID().toString(), tradeId, 1, Outcome.REJECTED,
                List.of(new Violation(ReasonCode.LOWER_VERSION, "version", "version 1 is lower than stored version 2")),
                Channel.STREAM, Instant.parse("2026-11-19T10:15:31Z"), "{}", null);
    }

    private void inRolledBackTransaction(Runnable work) {
        tx.executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
    }

    @Test
    @Tag("US-501.1")
    void should_returnAppendedEventsInOrder_when_batchIsClaimed() {
        inRolledBackTransaction(() -> {
            // Arrange
            String tradeId = "T-" + UUID.randomUUID();
            AuditEvent first = accepted(tradeId);
            AuditEvent second = rejected(tradeId);
            outbox.append(first);
            outbox.append(second);

            // Act
            List<AuditEvent> claimed = outbox.claimBatch(1000);

            // Assert: a full round trip through JSONB, in insertion order
            assertThat(claimed).filteredOn(e -> tradeId.equals(e.tradeId())).containsExactly(first, second);
        });
    }

    @Test
    @Tag("US-501.1")
    void should_notReturnEvents_when_theyHaveBeenDeleted() {
        inRolledBackTransaction(() -> {
            // Arrange
            AuditEvent event = accepted("T-" + UUID.randomUUID());
            outbox.append(event);

            // Act
            outbox.delete(List.of(event.eventId()));

            // Assert
            assertThat(outbox.claimBatch(1000)).extracting(AuditEvent::eventId).doesNotContain(event.eventId());
        });
    }

    @Test
    @Tag("US-501.1")
    void should_doNothing_when_noEventIdsAreGiven() {
        // Act + Assert: an empty batch must not produce invalid SQL ("IN ()")
        assertThatCode(() -> outbox.delete(List.of())).doesNotThrowAnyException();
    }

    @Test
    @Tag("US-501.3")
    void should_listEachWaitingTradeOnceOldestFirst_when_eventsAreWaiting() {
        inRolledBackTransaction(() -> {
            // Arrange: two events for one trade, then one for another
            String first = "T-" + UUID.randomUUID();
            String second = "T-" + UUID.randomUUID();
            outbox.append(accepted(first));
            outbox.append(rejected(first));
            outbox.append(accepted(second));

            // Act
            List<String> pending = outbox.pendingTradeIds(1000);

            // Assert
            assertThat(pending).filteredOn(id -> id.equals(first) || id.equals(second)).containsExactly(first, second);
        });
    }

    @Test
    @Tag("A-46")
    void should_reportSizeAndOldestEvent_when_eventsAreWaiting() {
        inRolledBackTransaction(() -> {
            // Arrange: two waiting events, the older recorded at 10:15:30.123456Z (see accepted())
            outbox.append(accepted("T-" + UUID.randomUUID()));
            outbox.append(rejected("T-" + UUID.randomUUID()));

            // Act
            OutboxBacklog backlog = outbox.backlog();

            // Assert (other ITs' rows may be waiting too, so at least ours, and nothing newer than ours is oldest)
            assertThat(backlog.size()).isGreaterThanOrEqualTo(2);
            assertThat(backlog.oldest()).hasValueSatisfying(
                    oldest -> assertThat(oldest).isBeforeOrEqualTo(Instant.parse("2026-11-19T10:15:30.123456Z")));
        });
    }
}
