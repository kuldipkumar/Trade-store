package com.tradestore.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Builds audit events with a fresh ID, a UTC timestamp from the Clock and a size-capped payload. */
@Component
public class AuditEventFactory {

    private final Clock clock;
    private final int maxPayloadChars;

    @Autowired
    public AuditEventFactory(Clock clock, TradeStoreProperties properties) {
        this(clock, properties.audit().maxPayloadChars());
    }

    AuditEventFactory(Clock clock, int maxPayloadChars) {
        this.clock = clock;
        this.maxPayloadChars = maxPayloadChars;
    }

    /** An ACCEPTED or REPLACED trade. */
    public AuditEvent stored(Trade trade, Outcome outcome, TradeSubmission submission) {
        return new AuditEvent(newId(), trade.tradeId(), trade.version(), outcome, List.of(), submission.channel(),
                clock.instant(), cap(submission.rawPayload()), trade);
    }

    public AuditEvent rejected(TradeSubmission submission, List<Violation> violations) {
        return new AuditEvent(newId(), submission.tradeId(), submission.version(), Outcome.REJECTED, violations,
                submission.channel(), clock.instant(), cap(submission.rawPayload()), null);
    }

    /** An EXPIRED trade, from the expiry job: channel SCHEDULER, no payload (A-20, decision D4). */
    public AuditEvent expired(Trade trade) {
        return new AuditEvent(newId(), trade.tradeId(), trade.version(), Outcome.EXPIRED, List.of(), Channel.SCHEDULER,
                clock.instant(), null, trade);
    }

    /**
     * A message moved to the dead-letter topic (US-302, US-303): it has no trusted trade identity, so the event is
     * keyed by its position, {@code topic-partition-offset}, which makes a repeated recovery idempotent (D5).
     */
    public AuditEvent deadLettered(String position, Outcome outcome, String payload, String error) {
        return new AuditEvent(position, null, null, outcome, List.of(), Channel.STREAM, clock.instant(), payload, null,
                error);
    }

    private String cap(String payload) {
        return payload == null || payload.length() <= maxPayloadChars ? payload : payload.substring(0, maxPayloadChars);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
