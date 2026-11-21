package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeNotFoundException;
import com.tradestore.persistence.TradeRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The read side for trades (US-402). */
class TradeQueryServiceTest {

    private final TradeRepository trades = mock(TradeRepository.class);
    private final TradeQueryService service = new TradeQueryService(trades);

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
}
