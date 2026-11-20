package com.tradestore.service;

import java.util.List;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.persistence.AuditEventStore;
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

    @Autowired
    public OutboxRelayService(OutboxRepository outbox, AuditEventStore audit, TradeHistoryStore history,
                              TradeStoreProperties properties) {
        this(outbox, audit, history, properties.outbox().batchSize());
    }

    OutboxRelayService(OutboxRepository outbox, AuditEventStore audit, TradeHistoryStore history, int batchSize) {
        this.outbox = outbox;
        this.audit = audit;
        this.history = history;
        this.batchSize = batchSize;
    }

    /** @return the number of events relayed */
    @Transactional
    public int relayBatch() {
        List<AuditEvent> events = outbox.claimBatch(batchSize);
        if (events.isEmpty()) {
            return 0;
        }
        for (AuditEvent event : events) {
            audit.insert(event);
            if (event.outcome() == Outcome.ACCEPTED || event.outcome() == Outcome.REPLACED) {
                history.insert(TradeHistoryEntry.from(event));
            }
        }
        outbox.delete(events.stream().map(AuditEvent::eventId).toList());
        return events.size();
    }
}
