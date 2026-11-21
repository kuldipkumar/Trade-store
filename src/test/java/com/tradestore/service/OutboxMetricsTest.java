package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.tradestore.persistence.OutboxBacklog;
import com.tradestore.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** A-46 / docs/05-lld.md section 8: the outbox backlog gauges. */
class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");

    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void bind() {
        new OutboxMetrics(outbox, Clock.fixed(NOW, ZoneOffset.UTC)).bindTo(registry);
    }

    private double gauge(String name) {
        Gauge gauge = registry.find(name).gauge();
        return gauge == null ? -1 : gauge.value();
    }

    @Test
    @Tag("A-46")
    void should_reportBacklogSizeAndOldestAge_when_eventsAreWaiting() {
        // Arrange
        when(outbox.backlog()).thenReturn(new OutboxBacklog(5, Optional.of(NOW.minusSeconds(90))));

        // Act + Assert
        assertThat(gauge("trade.outbox.backlog.size")).isEqualTo(5.0);
        assertThat(gauge("trade.outbox.oldest.age")).isEqualTo(90.0);
    }

    @Test
    @Tag("A-46")
    void should_reportZeroAge_when_theOutboxIsEmpty() {
        // Arrange
        when(outbox.backlog()).thenReturn(new OutboxBacklog(0, Optional.empty()));

        // Act + Assert
        assertThat(gauge("trade.outbox.backlog.size")).isZero();
        assertThat(gauge("trade.outbox.oldest.age")).isZero();
    }

    @Test
    @Tag("A-46")
    void should_reportNoValueWithoutFailingTheScrape_when_theDatabaseIsDown() {
        // Arrange
        when(outbox.backlog()).thenThrow(new DataAccessResourceFailureException("PostgreSQL down"));

        // Act + Assert
        assertThat(gauge("trade.outbox.backlog.size")).isNaN();
        assertThat(gauge("trade.outbox.oldest.age")).isNaN();
    }

    @Test
    @Tag("A-46")
    void should_queryTheBacklogOncePerScrape_when_bothGaugesAreRead() {
        // Arrange
        when(outbox.backlog()).thenReturn(new OutboxBacklog(5, Optional.of(NOW.minusSeconds(90))));

        // Act: one scrape reads both gauges (same instant)
        gauge("trade.outbox.backlog.size");
        gauge("trade.outbox.oldest.age");

        // Assert: one query, so both values come from the same moment
        verify(outbox, times(1)).backlog();
    }

    @Test
    @Tag("A-46")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logWhyTheBacklogIsUnknown_when_theQueryFails(CapturedOutput output) {
        // Arrange
        when(outbox.backlog()).thenThrow(new DataAccessResourceFailureException("permission denied for outbox_events"));

        // Act
        gauge("trade.outbox.backlog.size");

        // Assert
        assertThat(output).contains("Outbox backlog could not be read").contains("permission denied for outbox_events");
    }
}
