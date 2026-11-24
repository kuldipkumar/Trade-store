package com.tradestore.persistence;

import com.tradestore.domain.AuditEvent;

/**
 * An outbox row: the event, and the W3C traceparent of the transaction that wrote it, so the relay can continue that
 * trace when it writes to MongoDB (ADR-005, ADR-012).
 *
 * @param traceparent null when the event was written outside a trace
 */
public record OutboxEntry(AuditEvent event, String traceparent) {
}
