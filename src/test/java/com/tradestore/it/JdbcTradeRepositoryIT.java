package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.persistence.UpsertResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The version-guarded upsert against real PostgreSQL (ADR-003, docs/05-lld.md section 4.1). */
@IntegrationTest
class JdbcTradeRepositoryIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate MATURITY = LocalDate.of(2027, 5, 20);

    private final TradeRepository repository;

    private final JdbcClient jdbc;

    private final String tradeId = "T-" + UUID.randomUUID();

    JdbcTradeRepositoryIT(TradeRepository repository, JdbcClient jdbc) {
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
}
