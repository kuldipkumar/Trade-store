package com.tradestore.service;

import java.util.List;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.observability.TraceContext;
import com.tradestore.persistence.OutboxEntry;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeHistoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves one batch of outcomes from the PostgreSQL outbox to MongoDB (ADR-005). The claimed rows stay locked
 * until the transaction ends; they are deleted only after every MongoDB write succeeded. MongoDB writes are
 * keyed by eventId, so re-relaying after a crash creates no duplicates.
 */
@Service
public class OutboxRelayService {

    private final OutboxRepository outbox;
    private final AuditEventStore audit;
    private final TradeHistoryStore history;
    private final int batchSize;
    private final TraceContext trace;

    @Autowired
    public OutboxRelayService(OutboxRepository outbox, AuditEventStore audit, TradeHistoryStore history,
                              TradeStoreProperties properties, TraceContext trace) {
        this(outbox, audit, history, properties.outbox().batchSize(), trace);
    }

    OutboxRelayService(OutboxRepository outbox, AuditEventStore audit, TradeHistoryStore history, int batchSize,
                       TraceContext trace) {
        this.outbox = outbox;
        this.audit = audit;
        this.history = history;
        this.batchSize = batchSize;
        this.trace = trace;
    }

    /** @return the number of events relayed */
    @Transactional
    public int relayBatch() {
        List<OutboxEntry> entries = outbox.claimBatch(batchSize);
        if (entries.isEmpty()) {
            return 0;
        }
        for (OutboxEntry entry : entries) {
            AuditEvent event = entry.event();
            // Continues the trace of the transaction that wrote the row, so its MongoDB writes join that trade's trace
            trace.inSpan("outbox.relay", entry.traceparent(), tags -> {
                tags.tag("trade.id", String.valueOf(event.tradeId()));
                tags.tag("trade.outcome", event.outcome().name());
                audit.insert(event);
                if (event.outcome() == Outcome.ACCEPTED || event.outcome() == Outcome.REPLACED) {
                    history.insert(TradeHistoryEntry.from(event));
                }
                return null;
            });
        }
        outbox.delete(entries.stream().map(e -> e.event().eventId()).toList());
        return entries.size();
    }
}
