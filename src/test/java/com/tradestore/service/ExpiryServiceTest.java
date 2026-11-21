package com.tradestore.service;

import static com.tradestore.TestTrades.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** US-202: the expiry use case (docs/05-lld.md section 4.2). */
class ExpiryServiceTest {

    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(0, 5).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final TradeRepository trades = mock(TradeRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CountingTransactions transactions = new CountingTransactions();
    private final ExpiryService service = new ExpiryService(trades, outbox, new AuditEventFactory(CLOCK, 10_000),
            transactions, CLOCK, new TradeMetrics(registry), 2);

    /** Runs each callback directly and counts the transactions. */
    static class CountingTransactions implements TransactionOperations {
        int count;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            count++;
            return action.doInTransaction(null);
        }
    }

    private static Trade expired(String tradeId) {
        return new Trade(tradeId, 1, "CP-1", "B1", LocalDate.of(2026, 11, 18), LocalDate.of(2026, 10, 1), true);
    }

    @Test
    @Tag("US-202.3")
    void should_recordOneExpiredEventPerTrade_when_tradesAreMarkedExpired() {
        // Arrange
        when(trades.markExpired(TODAY, 2)).thenReturn(List.of(expired("T1"), expired("T2")));

        // Act
        int count = service.expireMaturedTrades();

        // Assert
        assertThat(count).isEqualTo(2);
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(outbox, times(2)).append(events.capture());
        assertThat(events.getAllValues()).extracting(AuditEvent::tradeId, AuditEvent::outcome)
                .containsExactly(tuple("T1", Outcome.EXPIRED),
                        tuple("T2", Outcome.EXPIRED));
    }

    @Test
    @Tag("US-604.2")
    void should_countEachExpiredTrade_when_tradesAreMarkedExpired() {
        // Arrange
        when(trades.markExpired(TODAY, 2)).thenReturn(List.of(expired("T1"), expired("T2")));

        // Act
        service.expireMaturedTrades();

        // Assert (observability built in: AGENTS.md section 4)
        var counter = registry.find("trade.outcomes")
                .tags("outcome", "EXPIRED", "reason", "NONE", "channel", "SCHEDULER").counter();
        assertThat(counter == null ? 0 : counter.count()).isEqualTo(2.0);
    }

    @Test
    @Tag("US-604.3")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logOneOutcomeLinePerExpiredTrade_when_tradesAreMarkedExpired(CapturedOutput output) {
        // Arrange
        when(trades.markExpired(TODAY, 2)).thenReturn(List.of(expired("T1")));

        // Act
        service.expireMaturedTrades();

        // Assert: same format as ingestion (docs/05-lld.md section 8)
        assertThat(output).contains("outcome=EXPIRED tradeId=T1 version=1 channel=SCHEDULER");
    }

    @Test
    @Tag("US-202.1")
    void should_expireInBatchesEachInItsOwnTransaction_when_moreTradesThanABatchHaveMatured() {
        // Arrange: batch size 2, three matured trades
        when(trades.markExpired(TODAY, 2)).thenReturn(List.of(expired("T1"), expired("T2")), List.of(expired("T3")));

        // Act
        int count = service.expireMaturedTrades();

        // Assert: a full batch means "maybe more", a short batch ends the run
        assertThat(count).isEqualTo(3);
        assertThat(transactions.count).isEqualTo(2);
        verify(outbox, times(3)).append(any());
    }
}
