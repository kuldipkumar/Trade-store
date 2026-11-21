package com.tradestore.domain;

import java.time.Instant;
import java.util.List;

/**
 * One entry in the append-only audit trail (A-29, A-45). Written to the outbox in the same transaction as
 * the trade change, then relayed to MongoDB (ADR-005).
 *
 * @param eventId        unique ID; becomes the MongoDB _id, which makes relaying idempotent
 * @param reasons        the violations for a REJECTED outcome; empty otherwise
 * @param eventTimestamp when the outcome happened, in UTC (A-53)
 * @param payload        the original input, capped in size; never logged (A-32)
 * @param trade          the stored trade for ACCEPTED, REPLACED and EXPIRED outcomes; null otherwise
 * @param error          why a POISON or FAILED message was dead-lettered (exception class and message); null otherwise
 */
public record AuditEvent(
        String eventId,
        String tradeId,
        Integer version,
        Outcome outcome,
        List<Violation> reasons,
        Channel channel,
        Instant eventTimestamp,
        String payload,
        Trade trade,
        String error) {

    public AuditEvent {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** An outcome of a readable trade, which never carries an error. */
    public AuditEvent(String eventId, String tradeId, Integer version, Outcome outcome, List<Violation> reasons,
                      Channel channel, Instant eventTimestamp, String payload, Trade trade) {
        this(eventId, tradeId, version, outcome, reasons, channel, eventTimestamp, payload, trade, null);
    }
}
