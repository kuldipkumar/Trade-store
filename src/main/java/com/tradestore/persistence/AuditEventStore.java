package com.tradestore.persistence;

import com.tradestore.domain.AuditEvent;

/** The append-only audit trail (A-45). There are deliberately no update or delete operations. */
public interface AuditEventStore {

    /** Inserts the event, keyed by its eventId. Inserting the same event again is a no-op (idempotent). */
    void insert(AuditEvent event);
}
