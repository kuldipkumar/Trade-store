package com.tradestore.messaging;

import com.tradestore.domain.Outcome;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

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
        Throwable cause = exception instanceof ListenerExecutionFailedException && exception.getCause() != null
                ? exception.getCause() : exception;
        String position = record.topic() + "-" + record.partition() + "-" + record.offset();
        audit.insert(events.deadLettered(position, Outcome.POISON,
                record.value() == null ? null : record.value().toString(), cause.toString()));
    }
}
