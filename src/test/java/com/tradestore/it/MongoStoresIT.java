package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.TradeHistoryStore;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** Append-only audit and history stores against real MongoDB (ADR-004, docs/05-lld.md section 5). */
@IntegrationTest
class MongoStoresIT {

    @Autowired
    private AuditEventStore auditEvents;

    @Autowired
    private TradeHistoryStore history;

    @Autowired
    private MongoTemplate mongo;

    private final String tradeId = "T-" + UUID.randomUUID();

    private AuditEvent stored(int version, Outcome outcome, Instant at) {
        Trade trade = new Trade(tradeId, version, "CP-" + version, "B1", LocalDate.of(2027, 5, 20), LocalDate.of(2026, 11, 19), false);
        return new AuditEvent(UUID.randomUUID().toString(), tradeId, version, outcome, List.of(), Channel.STREAM, at, "{}", trade);
    }

    @Test
    void should_storeAuditEntryWithEventIdAsKey_when_eventIsInserted() {
        // Arrange
        AuditEvent event = stored(1, Outcome.ACCEPTED, Instant.parse("2026-11-19T10:15:30Z"));

        // Act
        auditEvents.insert(event);

        // Assert (US-501.1 fields)
        Document doc = mongo.getCollection("audit_events").find(new Document("_id", event.eventId())).first();
        assertThat(doc).isNotNull();
        assertThat(doc.getString("tradeId")).isEqualTo(tradeId);
        assertThat(doc.getInteger("version")).isEqualTo(1);
        assertThat(doc.getString("outcome")).isEqualTo("ACCEPTED");
        assertThat(doc.getString("channel")).isEqualTo("STREAM");
        assertThat(doc.getDate("eventTimestamp").toInstant()).isEqualTo(event.eventTimestamp());
        assertThat(doc.getString("payload")).isEqualTo("{}");
    }

    @Test
    void should_keepOneEntry_when_sameEventIsInsertedTwice() {
        // Arrange: a relay crash after the MongoDB write but before the outbox delete (ADR-005)
        AuditEvent event = stored(1, Outcome.ACCEPTED, Instant.parse("2026-11-19T10:15:30Z"));
        auditEvents.insert(event);

        // Act
        auditEvents.insert(event);

        // Assert
        assertThat(mongo.getCollection("audit_events").countDocuments(new Document("_id", event.eventId()))).isEqualTo(1);
    }

    @Test
    void should_returnHistoryOrderedByVersion_when_versionsArriveOutOfOrder() {
        // Arrange (US-102.3, US-404.1)
        TradeHistoryEntry v2 = TradeHistoryEntry.from(stored(2, Outcome.REPLACED, Instant.parse("2026-11-19T10:00:00Z")));
        TradeHistoryEntry v1 = TradeHistoryEntry.from(stored(1, Outcome.ACCEPTED, Instant.parse("2026-11-19T11:00:00Z")));

        // Act
        history.insert(v2);
        history.insert(v1);
        history.insert(v1);

        // Assert
        assertThat(history.findByTradeId(tradeId)).containsExactly(v1, v2);
    }

    @Test
    void should_haveReportAndHistoryIndexes_when_applicationStarts() {
        // Act
        List<String> auditIndexes = mongo.indexOps("audit_events").getIndexInfo().stream().map(IndexInfo::getName).toList();
        List<String> historyIndexes = mongo.indexOps("trade_history").getIndexInfo().stream().map(IndexInfo::getName).toList();

        // Assert (A-52)
        assertThat(auditIndexes).contains("eventTimestamp", "outcome_eventTimestamp", "tradeId_eventTimestamp");
        assertThat(historyIndexes).contains("tradeId_version_receivedTimestamp");
    }
}
