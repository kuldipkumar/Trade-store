package com.tradestore.persistence.nosql;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.persistence.TradeHistoryStore;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** MongoDB trade history, collection {@code trade_history} (docs/05-lld.md section 5). Insert-only. */
@Repository
public class MongoTradeHistoryStore implements TradeHistoryStore {

    static final String COLLECTION = "trade_history";

    private final MongoTemplate mongo;

    public MongoTradeHistoryStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void insert(TradeHistoryEntry entry) {
        Document doc = new Document("_id", entry.eventId())
                .append("tradeId", entry.tradeId())
                .append("version", entry.version())
                .append("counterPartyId", entry.counterPartyId())
                .append("bookId", entry.bookId())
                .append("maturityDate", entry.maturityDate().toString())
                .append("createdDate", entry.createdDate().toString())
                .append("outcome", entry.outcome().name())
                .append("channel", entry.channel().name())
                .append("receivedTimestamp", Date.from(entry.receivedTimestamp()));
        try {
            mongo.insert(doc, COLLECTION);
        } catch (DuplicateKeyException alreadyRecorded) {
            // Relayed before a crash: the entry already exists (ADR-005), so this is success.
        }
    }

    @Override
    public List<TradeHistoryEntry> findByTradeId(String tradeId) {
        Query query = Query.query(Criteria.where("tradeId").is(tradeId))
                .with(Sort.by("version", "receivedTimestamp"));
        return mongo.find(query, Document.class, COLLECTION).stream().map(MongoTradeHistoryStore::toEntry).toList();
    }

    private static TradeHistoryEntry toEntry(Document doc) {
        return new TradeHistoryEntry(
                doc.getString("_id"),
                doc.getString("tradeId"),
                doc.getInteger("version"),
                doc.getString("counterPartyId"),
                doc.getString("bookId"),
                LocalDate.parse(doc.getString("maturityDate")),
                LocalDate.parse(doc.getString("createdDate")),
                Outcome.valueOf(doc.getString("outcome")),
                Channel.valueOf(doc.getString("channel")),
                doc.getDate("receivedTimestamp").toInstant());
    }
}
