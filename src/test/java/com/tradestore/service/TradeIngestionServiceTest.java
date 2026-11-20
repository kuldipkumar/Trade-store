package com.tradestore.service;

import static com.tradestore.TestTrades.TODAY;
import static com.tradestore.TestTrades.validSubmission;
import static com.tradestore.TestTrades.withBookId;
import static com.tradestore.TestTrades.withTradeId;
import static com.tradestore.TestTrades.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.LowerVersionException;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeRejectedException;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.persistence.UpsertResult;
import com.tradestore.service.validation.CompositeTradeValidator;
import com.tradestore.service.validation.LowerVersionValidator;
import com.tradestore.service.validation.MandatoryFieldsValidator;
import com.tradestore.service.validation.MaturityDateFormatValidator;
import com.tradestore.service.validation.PositiveVersionValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** The single ingestion use case for both channels (docs/05-lld.md sections 3 and 4.1). */
class TradeIngestionServiceTest {

    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static final Trade EXPECTED_T1_V1 =
            new Trade("T1", 1, "CP-1", "B1", LocalDate.of(2027, 5, 20), TODAY, false);

    private final TradeRepository trades = mock(TradeRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RecordingTransactions tx = new RecordingTransactions();

    private final TradeIngestionService service = new TradeIngestionService(
            new CompositeTradeValidator(List.of(new MandatoryFieldsValidator(), new PositiveVersionValidator(),
                    new MaturityDateFormatValidator(), new LowerVersionValidator())),
            trades, outbox, new AuditEventFactory(CLOCK, 10_000), tx, CLOCK);

    /** Runs the callback directly and records whether it completed (i.e. would have committed). */
    static class RecordingTransactions implements TransactionOperations {
        boolean committed;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            T result = action.doInTransaction(null);
            committed = true;
            return result;
        }
    }

    private AuditEvent appendedEvent() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(outbox).append(captor.capture());
        return captor.getValue();
    }

    @Test
    void should_storeTradeAndRecordAccepted_when_tradeIsNew() {
        // Arrange (US-101.1)
        when(trades.findById("T1")).thenReturn(Optional.empty());
        when(trades.upsertIfNotOlder(EXPECTED_T1_V1)).thenReturn(UpsertResult.INSERTED);

        // Act
        IngestionResult result = service.ingest(validSubmission());

        // Assert
        assertThat(result).isEqualTo(new IngestionResult(Outcome.ACCEPTED, EXPECTED_T1_V1));
        AuditEvent event = appendedEvent();
        assertThat(event.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(event.trade()).isEqualTo(EXPECTED_T1_V1);
        assertThat(tx.committed).isTrue();
    }

    @Test
    void should_recordReplaced_when_storedTradeIsUpdated() {
        // Arrange (US-102.1, US-103.1)
        when(trades.findById("T1")).thenReturn(Optional.of(new StoredTrade("T1", 1, false)));
        when(trades.upsertIfNotOlder(any())).thenReturn(UpsertResult.UPDATED);

        // Act
        IngestionResult result = service.ingest(validSubmission());

        // Assert
        assertThat(result.outcome()).isEqualTo(Outcome.REPLACED);
        assertThat(appendedEvent().outcome()).isEqualTo(Outcome.REPLACED);
    }

    @Test
    void should_rejectWithoutWritingTheTrade_when_versionIsLower() {
        // Arrange (US-104.1, US-104.2, US-104.3)
        when(trades.findById("T1")).thenReturn(Optional.of(new StoredTrade("T1", 2, false)));

        // Act + Assert
        assertThatThrownBy(() -> service.ingest(withVersion(1)))
                .isInstanceOf(LowerVersionException.class)
                .hasMessage("Trade T1 version 1 is lower than stored version 2");
        verify(trades, never()).upsertIfNotOlder(any());
        AuditEvent event = appendedEvent();
        assertThat(event.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(event.reasons()).extracting("reason").containsExactly(ReasonCode.LOWER_VERSION);
    }

    @Test
    void should_commitTheRejectionBeforeThrowing_when_tradeIsRejected() {
        // Arrange: the REJECTED outbox row must not be rolled back by the exception (docs/05-lld.md section 3)
        when(trades.findById("T1")).thenReturn(Optional.of(new StoredTrade("T1", 2, false)));

        // Act
        assertThatThrownBy(() -> service.ingest(withVersion(1))).isInstanceOf(TradeRejectedException.class);

        // Assert
        assertThat(tx.committed).isTrue();
    }

    @Test
    void should_rejectAndAudit_when_fieldsAreInvalid() {
        // Arrange (US-105.1: the "is rejected" part)
        when(trades.findById("T1")).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.ingest(withBookId(" ")))
                .isExactlyInstanceOf(TradeRejectedException.class)
                .satisfies(e -> assertThat(((TradeRejectedException) e).reasons()).containsExactly(ReasonCode.INVALID_FIELD));
        assertThat(appendedEvent().outcome()).isEqualTo(Outcome.REJECTED);
        verify(trades, never()).upsertIfNotOlder(any());
    }

    @Test
    void should_notLookUpTheStoredTrade_when_tradeIdIsBlank() {
        // Act
        assertThatThrownBy(() -> service.ingest(withTradeId(""))).isInstanceOf(TradeRejectedException.class);

        // Assert
        verify(trades, never()).findById(anyString());
    }

    @Test
    void should_revalidateAgainstTheNewVersion_when_aHigherVersionWasWrittenConcurrently() {
        // Arrange (US-106.2): validation passes against v1, but v3 is committed before our write
        when(trades.findById("T1"))
                .thenReturn(Optional.of(new StoredTrade("T1", 1, false)))
                .thenReturn(Optional.of(new StoredTrade("T1", 3, false)));
        when(trades.upsertIfNotOlder(any())).thenReturn(UpsertResult.STALE);

        // Act + Assert
        assertThatThrownBy(() -> service.ingest(withVersion(2)))
                .isInstanceOf(LowerVersionException.class)
                .hasMessage("Trade T1 version 2 is lower than stored version 3");
        verify(trades, times(1)).upsertIfNotOlder(any());
        assertThat(appendedEvent().outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    void should_retryTheWriteOnce_when_revalidationStillPasses() {
        // Arrange (US-106.2): the first write lost a race, but the re-read version is still not higher
        when(trades.findById("T1")).thenReturn(Optional.of(new StoredTrade("T1", 1, false)));
        when(trades.upsertIfNotOlder(any())).thenReturn(UpsertResult.STALE).thenReturn(UpsertResult.UPDATED);

        // Act
        IngestionResult result = service.ingest(withVersion(2));

        // Assert
        assertThat(result.outcome()).isEqualTo(Outcome.REPLACED);
        verify(trades, times(2)).upsertIfNotOlder(any());
    }

    @Test
    void should_rejectAsLowerVersion_when_theWriteKeepsLosingRaces() {
        // Arrange: two stale writes in a row; give up after the one retry
        when(trades.findById("T1"))
                .thenReturn(Optional.of(new StoredTrade("T1", 1, false)))
                .thenReturn(Optional.of(new StoredTrade("T1", 1, false)))
                .thenReturn(Optional.of(new StoredTrade("T1", 4, false)));
        when(trades.upsertIfNotOlder(any())).thenReturn(UpsertResult.STALE);

        // Act + Assert
        assertThatThrownBy(() -> service.ingest(withVersion(2)))
                .isInstanceOf(LowerVersionException.class)
                .hasMessage("Trade T1 version 2 is lower than stored version 4");
        verify(trades, times(2)).upsertIfNotOlder(any());
    }
}
