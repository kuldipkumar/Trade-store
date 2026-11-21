package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.service.ExpiryService;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The expiry job against real PostgreSQL, outbox relay and MongoDB, with "today" pinned to 2026-11-19. */
@IntegrationTest
class ExpiryIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(10);

    private final ExpiryService expiry;
    private final TradeRepository trades;
    private final MongoTemplate mongo;
    private final JdbcClient jdbc;
    private final MutableClock clock;

    private final String prefix = "T-" + UUID.randomUUID().toString().substring(0, 8);
    private final String maturedYesterday = prefix + "-18";
    private final String maturesToday = prefix + "-19";
    private final String maturesTomorrow = prefix + "-20";

    ExpiryIT(ExpiryService expiry, TradeRepository trades, MongoTemplate mongo, JdbcClient jdbc, MutableClock clock) {
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
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> assertThat(expiredAuditEntries(maturedYesterday)).isEqualTo(1));

        // Act (A-42: idempotent)
        expiry.expireMaturedTrades();

        // Assert: nothing new waits in the outbox, and the audit trail still has exactly one entry
        assertThat(outboxRows(maturedYesterday)).isZero();
        assertThat(expiredAuditEntries(maturedYesterday)).isEqualTo(1);
        assertThat(expired(maturedYesterday)).isTrue();
    }
}
