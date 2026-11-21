package com.tradestore.persistence;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;

/** The append-only audit trail (A-45). There are deliberately no update or delete operations. */
public interface AuditEventStore {

    /** Inserts the event, keyed by its eventId. Inserting the same event again is a no-op (idempotent). */
    void insert(AuditEvent event);

    /** One page of matching entries, oldest first (US-502). The trade snapshot is not part of the audit document. */
    List<AuditEvent> find(AuditQuery query, int page, int size);

    /** Matching entries counted per outcome, computed by the database (US-502.3); outcomes with none are absent. */
    Map<Outcome, Long> countByOutcome(AuditQuery query);

    /**
     * Every matching entry, oldest first, read lazily from a database cursor (US-503, A-52). The caller must close
     * the stream.
     */
    Stream<AuditEvent> stream(AuditQuery query);
}
