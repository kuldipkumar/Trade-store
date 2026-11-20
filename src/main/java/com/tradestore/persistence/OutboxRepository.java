package com.tradestore.persistence;

import java.util.List;

import com.tradestore.domain.AuditEvent;

/** The transactional outbox (ADR-005). Callers own the transaction. */
public interface OutboxRepository {

    /** Records an outcome. Must be called in the same transaction as the trade change it describes. */
    void append(AuditEvent event);

    /**
     * Locks and returns up to {@code limit} events, oldest first. Rows locked by another transaction are
     * skipped, so several relay instances can run at once.
     */
    List<AuditEvent> claimBatch(int limit);

    void delete(List<String> eventIds);
}
