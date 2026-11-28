package com.tradestore.service;

import static com.tradestore.TestTrades.TODAY;
import static com.tradestore.TestTrades.validSubmission;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-501.1: every audit entry has an ID, the trade identity, outcome, reasons, channel, UTC time and payload. */
class AuditEventFactoryTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:15:30Z");

    private final AuditEventFactory factory = new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC), 20);

    @Test
    @Tag("US-501.1")
    void should_describeStoredTrade_when_tradeIsAccepted() {
        // Arrange
        Trade trade = Trade.newVersion(validSubmission(), LocalDate.of(2027, 5, 20), TODAY);

        // Act
        AuditEvent event = factory.stored(trade, Outcome.ACCEPTED, validSubmission());

        // Assert
        assertThat(UUID.fromString(event.eventId())).isNotNull();
        assertThat(event).usingRecursiveComparison().ignoringFields("eventId").isEqualTo(
                new AuditEvent(null, "T1", 1, Outcome.ACCEPTED, List.of(), Channel.REST, NOW, "{}", trade));
    }

    @Test
    @Tag("US-104.3")
    @Tag("US-501.1")
    void should_carryAllReasonsAndNoTrade_when_tradeIsRejected() {
        // Arrange
        List<Violation> violations = List.of(Violation.invalidField("bookId", "bookId is required"));

        // Act
        AuditEvent event = factory.rejected(validSubmission(), violations);

        // Assert
        assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(event.reasons()).isEqualTo(violations);
        assertThat(event.trade()).isNull();
        assertThat(event.eventTimestamp()).isEqualTo(NOW);
    }

    @Test
    @Tag("A-55")
    @Tag("US-105.1")
    @Tag("US-501.1")
    void should_recordNoTradeIdButKeepThePayload_when_theRejectedTradeIdCannotBeAStoredId() {
        // Arrange: NUL is rejected by PostgreSQL, and the outbox trade_id column holds 64 characters
        TradeSubmission withNul = new TradeSubmission("X\u0000Y", 1, "CP-1", "B1", "2027-05-20", Channel.REST, "raw");
        TradeSubmission tooLong = new TradeSubmission("T".repeat(65), 1, "CP-1", "B1", "2027-05-20", Channel.STREAM, "raw");
        List<Violation> violations = List.of(Violation.invalidField("tradeId", "tradeId is invalid"));

        // Act
        AuditEvent nul = factory.rejected(withNul, violations);
        AuditEvent longId = factory.rejected(tooLong, violations);

        // Assert: like a dead letter, it has no trusted trade identity; the raw payload keeps what was received
        assertThat(nul.tradeId()).isNull();
        assertThat(longId.tradeId()).isNull();
        assertThat(longId.payload()).isEqualTo("raw");
        assertThat(longId.reasons()).isEqualTo(violations);
    }

    @Test
    @Tag("US-302.2")
    void should_capPayload_when_payloadIsLongerThanTheLimit() {
        // Arrange
        TradeSubmission big = new TradeSubmission("T1", 1, "CP-1", "B1", "2027-05-20", Channel.STREAM, "x".repeat(50));

        // Act
        AuditEvent event = factory.rejected(big, List.of(Violation.invalidField("bookId", "x")));

        // Assert
        assertThat(event.payload()).hasSize(20);
    }

    @Test
    @Tag("US-501.1")
    void should_giveEachEventItsOwnId_when_calledTwice() {
        // Act
        AuditEvent first = factory.rejected(validSubmission(), List.of(Violation.invalidField("bookId", "x")));
        AuditEvent second = factory.rejected(validSubmission(), List.of(Violation.invalidField("bookId", "x")));

        // Assert
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    @Tag("US-202.3")
    void should_describeTheExpiredTrade_when_aTradeExpires() {
        // Arrange
        Trade trade = new Trade("T1", 2, "CP-1", "B1", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 10, 1), true);

        // Act
        AuditEvent event = factory.expired(trade);

        // Assert (A-20; channel SCHEDULER per docs/05-lld.md decision D4)
        assertThat(event).isNotNull();
        assertThat(UUID.fromString(event.eventId())).isNotNull();
        assertThat(event).usingRecursiveComparison().ignoringFields("eventId").isEqualTo(
                new AuditEvent(null, "T1", 2, Outcome.EXPIRED, List.of(), Channel.SCHEDULER, NOW, null, trade));
    }

    @Test
    @Tag("US-302.2")
    void should_keyTheEventByPositionAndKeepTheError_when_aMessageIsDeadLettered() {
        // Act
        AuditEvent event = factory.deadLettered("trades.inbound-0-42", Outcome.POISON, "not json", "Malformed: x");

        // Assert: no trade identity is trusted from an unreadable message
        assertThat(event).isEqualTo(new AuditEvent("trades.inbound-0-42", null, null, Outcome.POISON, List.of(),
                Channel.STREAM, NOW, "not json", null, "Malformed: x"));
    }

    @Test
    @Tag("US-302.2")
    void should_capThePayloadAtTheConfiguredSize_when_aMessageIsDeadLettered() {
        // Act: the factory is configured with a 20-character cap
        AuditEvent event = factory.deadLettered("t-0-1", Outcome.POISON, "x".repeat(25), "e");

        // Assert
        assertThat(event.payload()).isEqualTo("x".repeat(20));
    }

    @Test
    @Tag("US-302.2")
    void should_capTheErrorAtTheConfiguredSize_when_itCarriesALongMessage() {
        // Act: some exception messages embed the whole message (code review of PR #61)
        AuditEvent event = factory.deadLettered("t-0-1", Outcome.POISON, "p", "e".repeat(25));

        // Assert
        assertThat(event.error()).isEqualTo("e".repeat(20));
    }
}
