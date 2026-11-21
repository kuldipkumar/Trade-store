package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** The two upsert results of a forced race: who held the row lock first, and who waited on it. */
    private record Race(UpsertResult holder, UpsertResult waiter) {
    }

    /**
     * Forces a deterministic race on one trade row: the holder's transaction writes and keeps the row lock;
     * the waiter's upsert then blocks on exactly that lock (checked with pg_blocking_pids); then the holder
     * commits and the waiter re-evaluates the version guard (READ COMMITTED).
     */
    private Race race(Trade holderTrade, Trade waiterTrade) throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<UpsertResult> holder = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
                holderPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                UpsertResult result = repository.upsertIfNotOlder(holderTrade);
                holding.countDown();
                awaitOrFail(release, "the test never released the lock holder; rolling it back");
                return result;
            }), pool);
            if (!holding.await(10, TimeUnit.SECONDS)) {
                if (holder.isDone()) {
                    holder.join(); // rethrows the holder's own failure
                }
                throw new AssertionError("the lock holder did not write within 10 s");
            }

            CompletableFuture<UpsertResult> waiter = CompletableFuture.supplyAsync(
                    () -> repository.upsertIfNotOlder(waiterTrade), pool);
            await().atMost(Duration.ofSeconds(10)).until(() -> isBlockedBy(holderPid.get()));
            release.countDown();

            return new Race(holder.get(10, TimeUnit.SECONDS), waiter.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdown(); // not shutdownNow: never interrupt a commit in progress
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /** True when some session in this database is waiting on a lock held by the given backend. */
    private boolean isBlockedBy(int holderPid) {
        return jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity
                 WHERE datname = current_database() AND :holder = ANY(pg_blocking_pids(pid))
                """).param("holder", holderPid).query(Long.class).single() > 0;
    }

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, e);
        }
    }

    @Test
    @Tag("US-106.1")
    void should_keepTheHigherVersion_when_aLowerVersionWaitsOnAConcurrentHigherWrite() throws Exception {
        // Arrange
        repository.upsertIfNotOlder(trade(1, "CP-1", TODAY));

        // Act: v3 holds the row lock; v2 waits on it (A-23: guarded at the database level)
        Race race = race(trade(3, "CP-3", TODAY), trade(2, "CP-2", TODAY));

        // Assert: v3 wins; v2 writes nothing (STALE, which the service turns into LOWER_VERSION, US-106.2)
        assertThat(race.holder()).isEqualTo(UpsertResult.UPDATED);
        assertThat(race.waiter()).isEqualTo(UpsertResult.STALE);
        assertThat(repository.findTrade(tradeId).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    @Tag("US-106.1")
    void should_storeTheHigherVersion_when_itWaitsOnAConcurrentLowerWrite() throws Exception {
        // Arrange
        repository.upsertIfNotOlder(trade(1, "CP-1", TODAY));

        // Act: v2 holds the row lock; v3 waits on it
        Race race = race(trade(2, "CP-2", TODAY), trade(3, "CP-3", TODAY));

        // Assert: v2 was stored first, then replaced by v3 (US-106.1: "or replaced by v3")
        assertThat(race.holder()).isEqualTo(UpsertResult.UPDATED);
        assertThat(race.waiter()).isEqualTo(UpsertResult.UPDATED);
        assertThat(repository.findTrade(tradeId).orElseThrow().version()).isEqualTo(3);
    }

    @Test
    @Tag("US-202.1")
    void should_markOnlyTradesMaturedBeforeTodayAndReturnThem_when_expiryRuns() {
        // Arrange
        String live = tradeId + "-live";
        repository.upsertIfNotOlder(new Trade(tradeId, 1, "CP-1", "B1", TODAY.minusDays(1), TODAY.minusDays(30), false));
        repository.upsertIfNotOlder(new Trade(live, 1, "CP-1", "B1", TODAY, TODAY.minusDays(30), false));

        // Act
        List<Trade> expired = repository.markExpired(TODAY, 1000);

        // Assert: the returned row is the stored trade, now expired; the one maturing today is untouched
        assertThat(expired).filteredOn(t -> t.tradeId().startsWith(tradeId)).singleElement().satisfies(t -> {
            assertThat(t.tradeId()).isEqualTo(tradeId);
            assertThat(t.expired()).isTrue();
        });
        assertThat(repository.findById(live).orElseThrow().expired()).isFalse();
    }

    @Test
    @Tag("US-202.2")
    void should_returnNothing_when_expiryRunsAgainOnAnAlreadyExpiredTrade() {
        // Arrange
        repository.upsertIfNotOlder(new Trade(tradeId, 1, "CP-1", "B1", TODAY.minusDays(1), TODAY.minusDays(30), false));
        repository.markExpired(TODAY, 1000);

        // Act
        List<Trade> second = repository.markExpired(TODAY, 1000);

        // Assert
        assertThat(second).extracting(Trade::tradeId).doesNotContain(tradeId);
        assertThat(repository.findById(tradeId).orElseThrow().expired()).isTrue();
    }

    @Test
    @Tag("US-202.1")
    void should_expireAtMostTheBatchSize_when_moreTradesHaveMatured() {
        // Arrange: three trades matured in 1999, a period no other test uses
        LocalDate y2k = LocalDate.of(2000, 1, 1);
        for (int day = 29; day <= 31; day++) {
            repository.upsertIfNotOlder(new Trade(tradeId + "-" + day, 1, "CP-1", "B1", LocalDate.of(1999, 12, day),
                    LocalDate.of(1999, 1, 1), false));
        }

        // Act
        List<Trade> first = repository.markExpired(y2k, 2);
        List<Trade> second = repository.markExpired(y2k, 2);

        // Assert: bounded batches, so a large backlog never locks every matured trade at once
        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(repository.markExpired(y2k, 2)).isEmpty();
    }
}
