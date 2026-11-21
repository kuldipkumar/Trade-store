package com.tradestore.persistence.nosql;

import java.util.List;


import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes for report filters and history lookups at startup (A-52). Creating an index that
 * already exists is a no-op, so this is safe on every start and on several instances.
 */
@Component
public class MongoIndexInitializer implements InitializingBean {

    private final MongoTemplate mongo;

    public MongoIndexInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    private static final List<String> SUPERSEDED =
            List.of("eventTimestamp", "outcome_eventTimestamp", "tradeId_eventTimestamp");

    @Override
    public void afterPropertiesSet() {
        var audit = mongo.indexOps(MongoAuditEventStore.COLLECTION);
        // Each ends with _id so the report's sort (eventTimestamp, _id) is read from the index, never done in memory.
        audit.createIndex(new Index().on("eventTimestamp", Direction.ASC).on("_id", Direction.ASC)
                .named("eventTimestamp_id"));
        audit.createIndex(new Index().on("outcome", Direction.ASC).on("eventTimestamp", Direction.ASC)
                .on("_id", Direction.ASC).named("outcome_eventTimestamp_id"));
        audit.createIndex(new Index().on("tradeId", Direction.ASC).on("eventTimestamp", Direction.ASC)
                .on("_id", Direction.ASC).named("tradeId_eventTimestamp_id"));
        // Superseded by the indexes above; dropped so inserts don't maintain both.
        List<String> existing = audit.getIndexInfo().stream().map(IndexInfo::getName).toList();
        SUPERSEDED.stream().filter(existing::contains).forEach(audit::dropIndex);
        mongo.indexOps(MongoTradeHistoryStore.COLLECTION).createIndex(new Index()
                .on("tradeId", Direction.ASC).on("version", Direction.ASC).on("receivedTimestamp", Direction.ASC)
                .named("tradeId_version_receivedTimestamp"));
    }
}
