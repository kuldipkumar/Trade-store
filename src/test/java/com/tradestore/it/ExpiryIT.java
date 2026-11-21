package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.scheduler.ExpiryScheduler;
import com.tradestore.service.ExpiryService;
import com.tradestore.service.IngestionResult;
import com.tradestore.service.TradeIngestionService;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The expiry job against real PostgreSQL, outbox relay and MongoDB, with "today" pinned to 2026-11-19.
 * Pinning the shared test clock and running expiry affect the whole shared test database: every assertion is
 * scoped to this test's own trade IDs, and pinned-clock ITs must not run in parallel (Failsafe runs test
 * classes sequentially; do not enable parallel execution).
 */
@IntegrationTest
class ExpiryIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(10);

    private final ExpiryService expiry;
    private final TradeRepository trades;
    private final MongoTemplate mongo;
    private final JdbcClient jdbc;
    private final MutableClock clock;
    private final ExpiryScheduler scheduler;
    private final TradeIngestionService ingestion;

    private final String prefix = "T-" + UUID.randomUUID().toString().substring(0, 8);
    private final String maturedYesterday = prefix + "-18";
    private final String maturesToday = prefix + "-19";
    private final String maturesTomorrow = prefix + "-20";

    ExpiryIT(ExpiryService expiry, TradeRepository trades, MongoTemplate mongo, JdbcClient jdbc, MutableClock clock,
             ExpiryScheduler scheduler, TradeIngestionService ingestion) {
        this.scheduler = scheduler;
        this.ingestion = ingestion;
        this.expiry = expiry;
        this.trades = trades;
        this.mongo = mongo;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @BeforeEach
    void storeThreeLiveTrades() {
        clock.setToday(TODAY);
        store(maturedYesterday, TODAY.minusDays(1));
        store(maturesToday, TODAY);
        store(maturesTomorrow, TODAY.plusDays(1));
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private void store(String tradeId, LocalDate maturity) {
        trades.upsertIfNotOlder(new Trade(tradeId, 1, "CP-1", "B1", maturity, TODAY.minusDays(30), false));
    }

    private boolean expired(String tradeId) {
        return trades.findById(tradeId).map(StoredTrade::expired).orElseThrow();
    }

    private long expiredAuditEntries(String tradeId) {
        return mongo.getCollection("audit_events")
                .countDocuments(new Document("tradeId", tradeId).append("outcome", "EXPIRED"));
    }

    private long outboxRows(String tradeId) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE trade_id = ?").param(tradeId).query(Long.class).single();
    }

    @Test
    @Tag("US-603.2")
    @Tag("US-202.1")
    void should_expireOnlyTradesMaturedBeforeToday_when_jobRuns() {
        // Act
        expiry.expireMaturedTrades();

        // Assert (A-17: expired when maturityDate < today; today itself is still live)
        assertThat(expired(maturedYesterday)).isTrue();
        assertThat(expired(maturesToday)).isFalse();
        assertThat(expired(maturesTomorrow)).isFalse();
    }

    @Test
    @Tag("US-202.3")
    void should_recordAnExpiredAuditEntry_when_aTradeIsExpired() {
        // Act
        expiry.expireMaturedTrades();

        // Assert (A-20)
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> assertThat(expiredAuditEntries(maturedYesterday)).isEqualTo(1));
    }

    @Test
    @Tag("US-202.2")
    void should_changeNothingAndWriteNoDuplicateAudit_when_jobRunsAgain() {
        // Arrange
        expiry.expireMaturedTrades();
        // The relay writes MongoDB first and deletes the outbox row on commit: wait for both.
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> {
            assertThat(expiredAuditEntries(maturedYesterday)).isEqualTo(1);
            assertThat(outboxRows(maturedYesterday)).isZero();
        });

        // Act (A-42: idempotent)
        expiry.expireMaturedTrades();

        // Assert: nothing new waits in the outbox, and the audit trail still has exactly one entry
        assertThat(outboxRows(maturedYesterday)).isZero();
        assertThat(expiredAuditEntries(maturedYesterday)).isEqualTo(1);
        assertThat(expired(maturedYesterday)).isTrue();
    }

    @Test
    @Tag("US-203.1")
    void should_expireTradesMaturedDuringDowntime_when_theServiceStarts() {
        // Act: the startup hook, as after a restart over midnight (called directly: firing a second
        // ApplicationReadyEvent into the shared context would re-run every other ready listener)
        scheduler.onApplicationReady();

        // Assert: expired shortly after startup, on the task scheduler, without waiting for the cron (A-18, A-51)
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> assertThat(expired(maturedYesterday)).isTrue());
        assertThat(expired(maturesToday)).isFalse();
        assertThat(expired(maturesTomorrow)).isFalse();
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> assertThat(expiredAuditEntries(maturedYesterday)).isEqualTo(1));
    }

    private void markExpiredDirectly(String tradeId) {
        jdbc.sql("UPDATE trades SET expired = true WHERE trade_id = ?").param(tradeId).update();
    }

    @Test
    @Tag("US-204.1")
    void should_storeTheNewVersionAsLive_when_anExpiredTradeIsReplacedByAValidVersion() {
        // Arrange: v1 is stored and expired
        expiry.expireMaturedTrades();
        assertThat(expired(maturedYesterday)).isTrue();

        // Act: v2 with maturity 2027-01-01 (A-09)
        IngestionResult result = ingestion.ingest(
                new TradeSubmission(maturedYesterday, 2, "CP-1", "B1", "2027-01-01", Channel.REST, "{}"));

        // Assert: replaced, and live again (A-19: only a new valid version resets expired)
        assertThat(result.outcome()).isEqualTo(Outcome.REPLACED);
        assertThat(trades.findById(maturedYesterday)).contains(new StoredTrade(maturedYesterday, 2, false));

        // ...and really extended: the new maturity is stored, so the next expiry run leaves it live
        assertThat(trades.findTrade(maturedYesterday).orElseThrow().maturityDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        expiry.expireMaturedTrades();
        assertThat(expired(maturedYesterday)).isFalse();
    }

    @Test
    @Tag("US-204.2")
    void should_neverSetExpiredBackToN_when_theJobRunsOnAnExpiredTrade() {
        // Arrange: a trade already marked expired, even one whose maturity is not yet past
        markExpiredDirectly(maturesTomorrow);

        // Act
        expiry.expireMaturedTrades();

        // Assert
        assertThat(expired(maturesTomorrow)).isTrue();
    }
}
