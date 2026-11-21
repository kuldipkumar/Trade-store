package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import com.tradestore.service.parsing.MalformedTradeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/** US-302: an unreadable message is published to the dead-letter topic, audited as POISON and counted. */
class DeadLetterAuditRecovererTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:15:30Z");

    private final ConsumerRecordRecoverer publisher = mock(ConsumerRecordRecoverer.class);
    private final AuditEventStore audit = mock(AuditEventStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DeadLetterAuditRecoverer recoverer = new DeadLetterAuditRecoverer(publisher,
            new AuditEventFactory(Clock.fixed(NOW, ZoneOffset.UTC), new TradeStoreProperties(null, null, null, null, null, null)),
            audit, registry);

    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("trades.inbound", 3, 42L, "T1", "{bad");
    private final Exception failure = new ListenerExecutionFailedException("listener failed",
            new MalformedTradeException("The body is not valid JSON"));

    @Test
    @Tag("US-302.1")
    void should_publishTheRecordToTheDeadLetterTopic_when_itIsRecovered() {
        // Act
        recoverer.accept(record, failure);

        // Assert
        verify(publisher).accept(record, failure);
    }

    @Test
    @Tag("US-302.2")
    void should_auditAPoisonEntryKeyedByPositionWithTheCause_when_aMalformedMessageIsRecovered() {
        // Act
        recoverer.accept(record, failure);

        // Assert: the cause, not the listener wrapper, is the reason
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).insert(event.capture());
        assertThat(event.getValue()).isEqualTo(new AuditEvent("trades.inbound-3-42", null, null, Outcome.POISON,
                List.of(), Channel.STREAM, NOW, "{bad", null,
                "com.tradestore.service.parsing.MalformedTradeException: The body is not valid JSON"));
    }

    @Test
    @Tag("US-302.2")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logAtErrorWithoutThrowing_when_theAuditCannotBeWritten(CapturedOutput output) {
        // Arrange: MongoDB is down; the dead-letter record is already the durable copy (ADR-005 section 3)
        doThrow(new DataAccessResourceFailureException("MongoDB down")).when(audit).insert(any());

        // Act + Assert: throwing would make the container redeliver and dead-letter the message again
        assertThatCode(() -> recoverer.accept(record, failure)).doesNotThrowAnyException();
        assertThat(output).contains("ERROR").contains("trades.inbound-3-42").doesNotContain("{bad");
    }

    @Test
    @Tag("US-302.1")
    @Tag("US-604.2")
    @ExtendWith(OutputCaptureExtension.class)
    void should_countAndLogTheDeadLetterWithoutThePayload_when_aMessageIsRecovered(CapturedOutput output) {
        // Act
        recoverer.accept(record, failure);

        // Assert: trade_deadletter_total{type=POISON}, and one WARN line with the position only (A-32)
        assertThat(registry.counter("trade.deadletter", "type", "POISON").count()).isEqualTo(1.0);
        assertThat(output).contains("WARN").contains("outcome=POISON position=trades.inbound-3-42")
                .contains("MalformedTradeException").doesNotContain("{bad");
    }
}
