package com.tradestore.persistence.nosql;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
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

    @Override
    public void afterPropertiesSet() {
        var audit = mongo.indexOps(MongoAuditEventStore.COLLECTION);
        audit.createIndex(new Index().on("eventTimestamp", Direction.ASC).named("eventTimestamp"));
        audit.createIndex(new Index().on("outcome", Direction.ASC).on("eventTimestamp", Direction.ASC)
                .named("outcome_eventTimestamp"));
        audit.createIndex(new Index().on("tradeId", Direction.ASC).on("eventTimestamp", Direction.ASC)
                .named("tradeId_eventTimestamp"));
        mongo.indexOps(MongoTradeHistoryStore.COLLECTION).createIndex(new Index()
                .on("tradeId", Direction.ASC).on("version", Direction.ASC).on("receivedTimestamp", Direction.ASC)
                .named("tradeId_version_receivedTimestamp"));
    }
}
