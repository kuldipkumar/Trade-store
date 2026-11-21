package com.tradestore.persistence.nosql;

import java.util.Date;
import java.util.List;

import com.tradestore.domain.AuditEvent;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.AuditQuery;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** MongoDB audit trail, collection {@code audit_events} (docs/05-lld.md section 5). Insert-only. */
@Repository
public class MongoAuditEventStore implements AuditEventStore {

    static final String COLLECTION = "audit_events";

    private final MongoTemplate mongo;

    public MongoAuditEventStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void insert(AuditEvent event) {
        Document doc = new Document("_id", event.eventId())
                .append("tradeId", event.tradeId())
                .append("version", event.version())
                .append("outcome", event.outcome().name())
                .append("reasons", event.reasons().stream()
                        .map(v -> new Document("reason", v.reason().name()).append("field", v.field()).append("message", v.message()))
                        .toList())
                .append("channel", event.channel().name())
                .append("eventTimestamp", Date.from(event.eventTimestamp()))
                .append("payload", event.payload());
        if (event.error() != null) {
            doc.append("error", event.error());
        }
        try {
            mongo.insert(doc, COLLECTION);
        } catch (DuplicateKeyException alreadyRecorded) {
            // Relayed before a crash: the entry already exists (ADR-005), so this is success.
        }
    }

    @Override
    public List<AuditEvent> find(AuditQuery query, int page, int size) {
        return List.of();
    }
}
