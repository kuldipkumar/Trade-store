package com.tradestore.persistence.nosql;

import java.util.Date;
import java.util.List;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.AuditQuery;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
        Query mongoQuery = Query.query(matching(query)).with(Sort.by("eventTimestamp", "_id"));
        return mongo.find(mongoQuery, Document.class, COLLECTION).stream().map(MongoAuditEventStore::toEvent).toList();
    }

    private static Criteria matching(AuditQuery query) {
        Criteria criteria = new Criteria();
        if (query.tradeId() != null) {
            criteria = criteria.and("tradeId").is(query.tradeId());
        }
        if (query.from() != null || query.until() != null) {
            Criteria time = criteria.and("eventTimestamp");
            if (query.from() != null) {
                time.gte(Date.from(query.from()));
            }
            if (query.until() != null) {
                time.lt(Date.from(query.until()));
            }
        }
        return criteria;
    }

    private static AuditEvent toEvent(Document doc) {
        List<Violation> reasons = doc.getList("reasons", Document.class, List.of()).stream()
                .map(r -> new Violation(ReasonCode.valueOf(r.getString("reason")), r.getString("field"), r.getString("message")))
                .toList();
        return new AuditEvent(doc.getString("_id"), doc.getString("tradeId"), doc.getInteger("version"),
                Outcome.valueOf(doc.getString("outcome")), reasons, Channel.valueOf(doc.getString("channel")),
                doc.getDate("eventTimestamp").toInstant(), doc.getString("payload"), null, doc.getString("error"));
    }
}
