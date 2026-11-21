package com.tradestore.messaging;

import java.util.function.Predicate;

import com.tradestore.domain.Outcome;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

/**
 * Moves a message the listener cannot process to the dead-letter topic, then records it in the audit trail as
 * POISON (unreadable, US-302) or FAILED (retries exhausted, US-303); ADR-005 section 3.
 */
public class DeadLetterAuditRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAuditRecoverer.class);

    private final ConsumerRecordRecoverer publisher;
    private final AuditEventFactory events;
    private final AuditEventStore audit;
    private final MeterRegistry registry;
    private final Predicate<Throwable> unreadable;

    /** @param unreadable true for a failure that retrying can't fix: such a message is POISON, others FAILED */
    public DeadLetterAuditRecoverer(ConsumerRecordRecoverer publisher, AuditEventFactory events, AuditEventStore audit,
                                    MeterRegistry registry, Predicate<Throwable> unreadable) {
        this.unreadable = unreadable;
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
        // Unreadable input is POISON (never retried); anything else came here after its retries ran out (US-303).
        Outcome outcome = unreadable.test(cause) ? Outcome.POISON : Outcome.FAILED;
        String position = record.topic() + "-" + record.partition() + "-" + record.offset();
        registry.counter("trade.deadletter", "type", outcome.name()).increment();
        log.warn("outcome={} position={} error={}", outcome, position, cause.getClass().getName());
        try {
            audit.insert(events.deadLettered(position, outcome,
                    record.value() == null ? null : record.value().toString(), cause.toString()));
        } catch (RuntimeException auditFailed) {
            registry.counter("trade.deadletter.audit.failures").increment();
            log.error("Dead-lettered message {} ({}) could not be audited; the dead-letter topic holds it: {}", position, outcome,
                    auditFailed.toString());
        }
    }
}
