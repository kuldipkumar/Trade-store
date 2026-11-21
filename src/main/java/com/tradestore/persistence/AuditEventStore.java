package com.tradestore.persistence;

import java.util.List;

import com.tradestore.domain.AuditEvent;

/** The append-only audit trail (A-45). There are deliberately no update or delete operations. */
public interface AuditEventStore {

    /** Inserts the event, keyed by its eventId. Inserting the same event again is a no-op (idempotent). */
    void insert(AuditEvent event);

    /** One page of matching entries, oldest first (US-502). The trade snapshot is not part of the audit document. */
    List<AuditEvent> find(AuditQuery query, int page, int size);
}
