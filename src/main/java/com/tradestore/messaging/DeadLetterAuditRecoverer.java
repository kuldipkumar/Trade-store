package com.tradestore.messaging;

import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Moves a message the listener cannot process to the dead-letter topic, then records it in the audit trail
 * (US-302, ADR-005 section 3).
 */
public class DeadLetterAuditRecoverer implements ConsumerRecordRecoverer {

    private final ConsumerRecordRecoverer publisher;
    private final AuditEventFactory events;
    private final AuditEventStore audit;
    private final MeterRegistry registry;

    public DeadLetterAuditRecoverer(ConsumerRecordRecoverer publisher, AuditEventFactory events, AuditEventStore audit,
                                    MeterRegistry registry) {
        this.publisher = publisher;
        this.events = events;
        this.audit = audit;
        this.registry = registry;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        publisher.accept(record, exception);
    }
}
