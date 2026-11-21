package com.tradestore.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import com.tradestore.service.parsing.MalformedTradeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
}
