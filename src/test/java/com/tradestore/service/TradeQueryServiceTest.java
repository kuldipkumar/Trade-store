package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.domain.TradeNotFoundException;
import com.tradestore.persistence.PageResult;
import com.tradestore.persistence.TradeHistoryStore;
import com.tradestore.persistence.TradeRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The read side for trades (US-402). */
class TradeQueryServiceTest {

    private final TradeRepository trades = mock(TradeRepository.class);
    private final TradeHistoryStore history = mock(TradeHistoryStore.class);
    private final TradeQueryService service = new TradeQueryService(trades, history, 100);

    @Test
    @Tag("US-402.1")
    void should_returnTheStoredTrade_when_itExists() {
        // Arrange
        Trade t1 = new Trade("T1", 2, "CP-1", "B1", LocalDate.of(2027, 5, 20), LocalDate.of(2026, 11, 19), false);
        when(trades.findTrade("T1")).thenReturn(Optional.of(t1));

        // Act + Assert
        assertThat(service.get("T1")).isEqualTo(t1);
    }

    @Test
    @Tag("US-402.2")
    void should_throwNotFound_when_noTradeIsStored() {
        // Arrange
        when(trades.findTrade("T9")).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.get("T9")).isInstanceOf(TradeNotFoundException.class).hasMessageContaining("T9");
    }

    @Test
    @Tag("US-403.1")
    void should_capThePageSize_when_aClientAsksForMoreThanTheMaximum() {
        // Arrange
        PageResult<Trade> page = new PageResult<>(List.of(), 0, 100, 0);
        when(trades.findPage(null, 0, 100)).thenReturn(page);

        // Act (A-52: results are paged; one request cannot pull the whole table)
        PageResult<Trade> result = service.list(null, 0, 5000);

        // Assert
        assertThat(result).isSameAs(page);
    }

    @Test
    @Tag("US-403.2")
    void should_passTheExpiredFilterThrough_when_listing() {
        // Arrange
        PageResult<Trade> page = new PageResult<>(List.of(), 1, 10, 0);
        when(trades.findPage(true, 1, 10)).thenReturn(page);

        // Act + Assert
        assertThat(service.list(true, 1, 10)).isSameAs(page);
    }

    @Test
    @Tag("US-404.1")
    void should_returnTheStoredVersionsInTheStoresOrder_when_historyExists() {
        // Arrange (the store orders by version, then received time)
        TradeHistoryEntry v1 = new TradeHistoryEntry("e1", "T2", 1, "CP-1", "B1", LocalDate.of(2027, 5, 20),
                LocalDate.of(2026, 11, 19), Outcome.ACCEPTED, Channel.REST, Instant.parse("2026-11-19T10:00:00Z"));
        TradeHistoryEntry v2 = new TradeHistoryEntry("e2", "T2", 2, "CP-2", "B1", LocalDate.of(2027, 5, 20),
                LocalDate.of(2026, 11, 19), Outcome.REPLACED, Channel.STREAM, Instant.parse("2026-11-19T11:00:00Z"));
        when(history.findByTradeId("T2")).thenReturn(List.of(v1, v2));

        // Act + Assert
        assertThat(service.history("T2")).containsExactly(v1, v2);
    }

    @Test
    @Tag("US-404.2")
    void should_throwNotFound_when_noHistoryExists() {
        // Arrange
        when(history.findByTradeId("T9")).thenReturn(List.of());

        // Act + Assert
        assertThatThrownBy(() -> service.history("T9")).isInstanceOf(TradeNotFoundException.class);
    }
}
