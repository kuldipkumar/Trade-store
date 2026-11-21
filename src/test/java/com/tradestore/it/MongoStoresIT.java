package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.domain.Violation;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.AuditQuery;
import com.tradestore.persistence.TradeHistoryStore;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;

/** Append-only audit and history stores against real MongoDB (ADR-004, docs/05-lld.md section 5). */
@IntegrationTest
class MongoStoresIT {

    private final AuditEventStore auditEvents;

    private final TradeHistoryStore history;

    private final MongoTemplate mongo;

    private final String tradeId = "T-" + UUID.randomUUID();

    MongoStoresIT(AuditEventStore auditEvents, TradeHistoryStore history, MongoTemplate mongo) {
        this.auditEvents = auditEvents;
        this.history = history;
        this.mongo = mongo;
    }

    private AuditEvent stored(int version, Outcome outcome, Instant at) {
        Trade trade = new Trade(tradeId, version, "CP-" + version, "B1", LocalDate.of(2027, 5, 20), LocalDate.of(2026, 11, 19), false);
        return new AuditEvent(UUID.randomUUID().toString(), tradeId, version, outcome, List.of(), Channel.STREAM, at, "{}", trade);
    }

    @Test
    @Tag("US-501.1")
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
    @Tag("US-302.2")
    void should_storeTheErrorOfADeadLetteredMessage_when_aPoisonEntryIsInserted() {
        // Arrange
        String position = "trades.inbound-0-" + UUID.randomUUID();
        AuditEvent event = new AuditEvent(position, null, null, Outcome.POISON, List.of(), Channel.STREAM,
                Instant.parse("2026-11-21T10:15:30Z"), "not json", null, "MalformedTradeException: not JSON");

        // Act
        auditEvents.insert(event);

        // Assert
        Document doc = mongo.getCollection("audit_events").find(new Document("_id", position)).first();
        assertThat(doc).isNotNull();
        assertThat(doc.getString("outcome")).isEqualTo("POISON");
        assertThat(doc.getString("error")).isEqualTo("MalformedTradeException: not JSON");
    }

    private AuditEvent audited(Outcome outcome, String at) {
        List<Violation> reasons = outcome == Outcome.REJECTED ? List.of(Violation.invalidField("bookId", "required")) : List.of();
        return new AuditEvent(UUID.randomUUID().toString(), tradeId, 1, outcome, reasons, Channel.REST,
                Instant.parse(at), "{}", null);
    }

    @Test
    @Tag("US-502.1")
    @Tag("US-502.2")
    void should_findEntriesInTheRangeOldestFirst_when_filteringOnTradeIdAndTime() {
        // Arrange
        AuditEvent before = audited(Outcome.ACCEPTED, "2026-11-01T09:59:59Z");
        AuditEvent first = audited(Outcome.ACCEPTED, "2026-11-01T10:00:00Z");
        AuditEvent second = audited(Outcome.REJECTED, "2026-11-01T11:00:00Z");
        AuditEvent atUntil = audited(Outcome.ACCEPTED, "2026-11-01T12:00:00Z");
        List.of(second, atUntil, before, first).forEach(auditEvents::insert);

        // Act: [from, until)
        List<AuditEvent> found = auditEvents.find(new AuditQuery(Instant.parse("2026-11-01T10:00:00Z"),
                Instant.parse("2026-11-01T12:00:00Z"), tradeId, null), 0, 10);

        // Assert: read back as written (the audit document holds no trade snapshot)
        assertThat(found).containsExactly(first, second);
    }

    @Test
    @Tag("US-502.2")
    @Tag("US-502.4")
    void should_returnTheRequestedPageOfOneOutcome_when_filteringOnOutcomeWithPaging() {
        // Arrange: three rejections and an acceptance
        AuditEvent r1 = audited(Outcome.REJECTED, "2026-11-02T10:00:00Z");
        AuditEvent r2 = audited(Outcome.REJECTED, "2026-11-02T11:00:00Z");
        AuditEvent r3 = audited(Outcome.REJECTED, "2026-11-02T12:00:00Z");
        List.of(r1, audited(Outcome.ACCEPTED, "2026-11-02T10:30:00Z"), r2, r3).forEach(auditEvents::insert);
        AuditQuery rejections = new AuditQuery(null, null, tradeId, Outcome.REJECTED);

        // Act
        List<AuditEvent> page0 = auditEvents.find(rejections, 0, 2);
        List<AuditEvent> page1 = auditEvents.find(rejections, 1, 2);

        // Assert
        assertThat(page0).containsExactly(r1, r2);
        assertThat(page1).containsExactly(r3);
    }

    @Test
    @Tag("US-502.3")
    void should_countMatchingEntriesPerOutcome_when_summarising() {
        // Arrange: inside the range 1 accepted and 2 rejected; one rejection outside it
        List.of(audited(Outcome.ACCEPTED, "2026-11-03T10:00:00Z"), audited(Outcome.REJECTED, "2026-11-03T11:00:00Z"),
                audited(Outcome.REJECTED, "2026-11-03T12:00:00Z"), audited(Outcome.REJECTED, "2026-11-04T12:00:00Z"))
                .forEach(auditEvents::insert);

        // Act
        Map<Outcome, Long> counts = auditEvents.countByOutcome(new AuditQuery(Instant.parse("2026-11-03T00:00:00Z"),
                Instant.parse("2026-11-04T00:00:00Z"), tradeId, null));

        // Assert
        assertThat(counts).isEqualTo(Map.of(Outcome.ACCEPTED, 1L, Outcome.REJECTED, 2L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"tradeId\": \"T1\"}", "{\"outcome\": \"REJECTED\"}"})
    @Tag("US-502.4")
    void should_sortTheReportFromAnIndex_when_queryingAPage(String filter) {
        // Act: the report's query shape (code review of PR #63)
        Document plan = mongo.getCollection("audit_events").find(Document.parse(filter))
                .sort(new Document("eventTimestamp", 1).append("_id", 1)).skip(50).limit(50).explain();

        // Assert: no in-memory sort of the whole matching set
        String winningPlan = plan.get("queryPlanner", Document.class).get("winningPlan", Document.class).toJson();
        assertThat(winningPlan).contains("IXSCAN").doesNotContain("\"SORT\"");
    }

    @Test
    @Tag("US-501.1")
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
    @Tag("US-102.3")
    @Tag("US-404.1")
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
    @Tag("A-52")
    void should_haveReportAndHistoryIndexes_when_applicationStarts() {
        // Act
        List<String> auditIndexes = mongo.indexOps("audit_events").getIndexInfo().stream().map(IndexInfo::getName).toList();
        List<String> historyIndexes = mongo.indexOps("trade_history").getIndexInfo().stream().map(IndexInfo::getName).toList();

        // Assert (A-52)
        assertThat(auditIndexes).contains("eventTimestamp_id", "outcome_eventTimestamp_id", "tradeId_eventTimestamp_id")
                .doesNotContain("eventTimestamp", "outcome_eventTimestamp", "tradeId_eventTimestamp");
        assertThat(historyIndexes).contains("tradeId_version_receivedTimestamp");
    }
}
