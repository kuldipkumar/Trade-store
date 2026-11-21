package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.persistence.UpsertResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** The version-guarded upsert against real PostgreSQL (ADR-003, docs/05-lld.md section 4.1). */
@IntegrationTest
class JdbcTradeRepositoryIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate MATURITY = LocalDate.of(2027, 5, 20);

    private final TradeRepository repository;

    private final JdbcClient jdbc;

    private final TransactionTemplate tx;

    private final String tradeId = "T-" + UUID.randomUUID();

    JdbcTradeRepositoryIT(TradeRepository repository, JdbcClient jdbc, TransactionTemplate tx) {
        this.tx = tx;
        this.repository = repository;
        this.jdbc = jdbc;
    }

    private Trade trade(int version, String counterPartyId, LocalDate createdDate) {
        return new Trade(tradeId, version, counterPartyId, "B1", MATURITY, createdDate, false);
    }

    @Test
    @Tag("US-101.1")
    void should_findNothing_when_tradeIsNotStored() {
        // Act + Assert
        assertThat(repository.findById(tradeId)).isEmpty();
        assertThat(repository.findTrade(tradeId)).isEmpty();
    }

    @Test
    @Tag("US-101.1")
    void should_insertTrade_when_noTradeIsStored() {
        // Act (US-101.1)
        UpsertResult result = repository.upsertIfNotOlder(trade(1, "CP-1", TODAY));

        // Assert
        assertThat(result).isEqualTo(UpsertResult.INSERTED);
        assertThat(repository.findTrade(tradeId)).contains(trade(1, "CP-1", TODAY));
        assertThat(repository.findById(tradeId)).contains(new StoredTrade(tradeId, 1, false));
    }

    @Test
    @Tag("US-102.1")
    void should_replaceTrade_when_versionIsHigher() {
        // Arrange
        repository.upsertIfNotOlder(trade(1, "CP-1", TODAY.minusDays(5)));

        // Act (US-102.1)
        UpsertResult result = repository.upsertIfNotOlder(trade(2, "CP-2", TODAY));

        // Assert
        assertThat(result).isEqualTo(UpsertResult.UPDATED);
        assertThat(repository.findTrade(tradeId)).contains(trade(2, "CP-2", TODAY));
    }

    @Test
    @Tag("US-103.1")
    void should_replaceTradeAndResetCreatedDate_when_versionIsTheSame() {
        // Arrange
        repository.upsertIfNotOlder(trade(2, "CP-1", TODAY.minusDays(18)));

        // Act (US-103.1)
        UpsertResult result = repository.upsertIfNotOlder(trade(2, "CP-9", TODAY));

        // Assert
        assertThat(result).isEqualTo(UpsertResult.UPDATED);
        assertThat(repository.findTrade(tradeId)).contains(trade(2, "CP-9", TODAY));
    }

    @Test
    @Tag("US-104.2")
    void should_leaveStoredTradeUnchanged_when_versionIsLower() {
        // Arrange
        repository.upsertIfNotOlder(trade(2, "CP-2", TODAY.minusDays(1)));

        // Act (US-104.2, US-106: the guard holds even if validation was bypassed by a race)
        UpsertResult result = repository.upsertIfNotOlder(trade(1, "CP-1", TODAY));

        // Assert
        assertThat(result).isEqualTo(UpsertResult.STALE);
        assertThat(repository.findTrade(tradeId)).contains(trade(2, "CP-2", TODAY.minusDays(1)));
    }

    @Test
    @Tag("US-204.1")
    void should_clearExpiredFlag_when_expiredTradeIsReplaced() {
        // Arrange
        repository.upsertIfNotOlder(trade(1, "CP-1", TODAY.minusDays(30)));
        jdbc.sql("UPDATE trades SET expired = true WHERE trade_id = ?").param(tradeId).update();

        // Act (US-204.1, A-09)
        repository.upsertIfNotOlder(trade(2, "CP-1", TODAY));

        // Assert
        assertThat(repository.findById(tradeId)).contains(new StoredTrade(tradeId, 2, false));
    }

    private long sessionsWaitingForAnUpsertLock() {
        return jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE wait_event_type = 'Lock' AND query LIKE 'INSERT INTO trades%'
                """).query(Long.class).single();
    }

    @Test
    @Tag("US-106.1")
    void should_keepTheHigherVersion_when_aLowerVersionWaitsOnAConcurrentHigherWrite() throws Exception {
        // Arrange: v1 stored; transaction A writes v3 and holds its row lock without committing
        repository.upsertIfNotOlder(trade(1, "CP-1", TODAY));
        CountDownLatch aHoldsTheLock = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<UpsertResult> a = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
                UpsertResult result = repository.upsertIfNotOlder(trade(3, "CP-3", TODAY));
                aHoldsTheLock.countDown();
                awaitQuietly(releaseA);
                return result;
            }), pool);
            assertThat(aHoldsTheLock.await(10, TimeUnit.SECONDS)).isTrue();

            // Act: B writes v2 and blocks on A's row lock (A-23: guarded at the database level)
            CompletableFuture<UpsertResult> b = CompletableFuture.supplyAsync(
                    () -> repository.upsertIfNotOlder(trade(2, "CP-2", TODAY)), pool);
            await().atMost(Duration.ofSeconds(10)).until(() -> sessionsWaitingForAnUpsertLock() > 0);
            releaseA.countDown();

            // Assert: v3 wins; v2 writes nothing and reports STALE, which the service turns into LOWER_VERSION (US-106.2)
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(UpsertResult.UPDATED);
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(UpsertResult.STALE);
            assertThat(repository.findTrade(tradeId).orElseThrow().version()).isEqualTo(3);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
