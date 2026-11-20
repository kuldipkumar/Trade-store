package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import com.tradestore.domain.Channel;
import com.tradestore.domain.LowerVersionException;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.persistence.TradeHistoryStore;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.service.IngestionResult;
import com.tradestore.service.TradeIngestionService;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * End to end through the real application: ingestion service, PostgreSQL (trade + outbox), outbox relay,
 * MongoDB (history + audit). The channels (REST, Kafka) are added by US-401 and US-301.
 */
@IntegrationTest
class StoreTradeIT {

    /** The relay polls every second; this is a test timeout, not an SLA (CR-4). */
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(10);

    private final TradeIngestionService ingestion;

    private final TradeRepository trades;

    private final TradeHistoryStore history;

    private final MongoTemplate mongo;

    private final Clock clock;

    private final String tradeId = "T-" + UUID.randomUUID();

    StoreTradeIT(TradeIngestionService ingestion, TradeRepository trades, TradeHistoryStore history, MongoTemplate mongo, Clock clock) {
        this.ingestion = ingestion;
        this.trades = trades;
        this.history = history;
        this.mongo = mongo;
        this.clock = clock;
    }

    private TradeSubmission submission(int version) {
        return new TradeSubmission(tradeId, version, "CP-1", "B1", "2099-05-20", Channel.REST, "{\"v\":" + version + "}");
    }

    private long auditEntries(Outcome outcome) {
        return mongo.getCollection("audit_events")
                .countDocuments(new Document("tradeId", tradeId).append("outcome", outcome.name()));
    }

    @Test
    @Tag("US-101.1")
    @Tag("US-101.3")
    @Tag("US-501.1")
    void should_storeTradeAndRecordHistoryAndAudit_when_newTradeIsSubmitted() {
        // Act (US-101.1)
        IngestionResult result = ingestion.ingest(submission(1));

        // Assert: stored in SQL with createdDate = today, not expired
        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        Trade stored = trades.findTrade(tradeId).orElseThrow();
        assertThat(stored.createdDate()).isEqualTo(LocalDate.now(clock));
        assertThat(stored.expired()).isFalse();

        // Assert (US-101.3, US-501.1): history and audit arrive in MongoDB through the outbox relay
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> {
            assertThat(history.findByTradeId(tradeId)).singleElement()
                    .satisfies(entry -> assertThat(entry.version()).isEqualTo(1));
            assertThat(auditEntries(Outcome.ACCEPTED)).isEqualTo(1);
        });
    }

    @Test
    @Tag("US-104.1")
    @Tag("US-104.2")
    @Tag("US-104.3")
    void should_keepStoredTradeAndAuditRejection_when_lowerVersionIsSubmitted() {
        // Arrange
        ingestion.ingest(submission(2));

        // Act (US-104.1)
        assertThatThrownBy(() -> ingestion.ingest(submission(1))).isInstanceOf(LowerVersionException.class);

        // Assert (US-104.2): the stored trade is unchanged
        assertThat(trades.findTrade(tradeId).orElseThrow().version()).isEqualTo(2);

        // Assert (US-104.3): the rejection is audited with its reason; history holds only v2
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> {
            Document rejected = mongo.getCollection("audit_events")
                    .find(new Document("tradeId", tradeId).append("outcome", "REJECTED")).first();
            assertThat(rejected).isNotNull();
            assertThat(rejected.getList("reasons", Document.class))
                    .extracting(r -> r.getString("reason")).containsExactly("LOWER_VERSION");
            assertThat(history.findByTradeId(tradeId)).extracting(e -> e.version()).containsExactly(2);
        });
    }
}
