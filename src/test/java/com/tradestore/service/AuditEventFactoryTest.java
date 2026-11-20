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
}
