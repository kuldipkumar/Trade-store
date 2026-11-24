package com.tradestore.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeRejectedException;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import com.tradestore.observability.TraceContext;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.persistence.UpsertResult;
import com.tradestore.service.validation.CompositeTradeValidator;
import com.tradestore.service.validation.MaturityDates;
import com.tradestore.service.validation.ValidationContext;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The single ingestion use case for the stream and REST channels (A-21, docs/05-lld.md sections 3 and 4.1).
 * In one transaction it validates the trade, writes it with a version guard, and records the outcome in the
 * outbox. A rejection is recorded and committed first, and only then thrown, so it is never rolled back.
 */
@Service
public class TradeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TradeIngestionService.class);

    /** One write plus one retry after losing a race to a concurrent higher version (US-106.2). */
    static final int MAX_WRITE_ATTEMPTS = 2;

    private final CompositeTradeValidator validator;
    private final TradeRepository trades;
    private final OutboxRepository outbox;
    private final AuditEventFactory events;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final TradeMetrics metrics;
    private final TraceContext trace;

    public TradeIngestionService(CompositeTradeValidator validator, TradeRepository trades, OutboxRepository outbox,
                                 AuditEventFactory events, TransactionOperations transactions, Clock clock,
                                 TradeMetrics metrics, TraceContext trace) {
        this.trace = trace;
        this.validator = validator;
        this.trades = trades;
        this.outbox = outbox;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @return the stored trade and whether it was ACCEPTED or REPLACED
     * @throws TradeRejectedException (or its subclass LowerVersionException) after the rejection is recorded
     */
    public IngestionResult ingest(TradeSubmission submission) {
        // One span per trade, tagged so Tempo can find it by trade ID (A-46, ADR-012)
        return trace.inSpan("trade.ingest", null, tags -> {
            tags.tag("trade.id", String.valueOf(submission.tradeId()));
            tags.tag("trade.version", String.valueOf(submission.version()));
            tags.tag("trade.channel", submission.channel().name());
            return ingestInSpan(submission, tags);
        });
    }

    private IngestionResult ingestInSpan(TradeSubmission submission, TraceContext.SpanTags tags) {
        Timer.Sample timing = metrics.startProcessing();
        LocalDate today = LocalDate.now(clock);
        Decision decision = transactions.execute(status -> decide(submission, today));
        tags.tag("trade.outcome", decision.outcome().name());
        metrics.recordOutcome(decision.outcome(), decision.reasons(), submission.channel());
        metrics.stopProcessing(timing, submission.channel());
        log.info("outcome={} tradeId={} version={} channel={} reasons={}",
                decision.outcome(), submission.tradeId(), submission.version(), submission.channel(), decision.reasons());
        if (decision.rejection() != null) {
            throw decision.rejection();
        }
        return decision.result();
    }

    private Decision decide(TradeSubmission submission, LocalDate today) {
        Optional<StoredTrade> stored = lookUp(submission);
        for (int attempt = 1; ; attempt++) {
            List<Violation> violations = validator.validate(new ValidationContext(submission, stored, today));
            if (!violations.isEmpty()) {
                return reject(submission, stored, violations);
            }
            Trade trade = Trade.newVersion(submission, MaturityDates.parse(submission.maturityDate()).orElseThrow(), today);
            UpsertResult result = trades.upsertIfNotOlder(trade);
            if (result != UpsertResult.STALE) {
                Outcome outcome = result == UpsertResult.INSERTED ? Outcome.ACCEPTED : Outcome.REPLACED;
                outbox.append(events.stored(trade, outcome, submission));
                return Decision.stored(new IngestionResult(outcome, trade));
            }
            // A higher version was committed concurrently: re-read and re-validate (US-106.2).
            stored = lookUp(submission);
            if (attempt == MAX_WRITE_ATTEMPTS) {
                StoredTrade latest = stored.orElseThrow();
                return reject(submission, stored, List.of(Violation.lowerVersion(submission.version(), latest.version())));
            }
        }
    }

    private Optional<StoredTrade> lookUp(TradeSubmission submission) {
        String tradeId = submission.tradeId();
        return tradeId == null || tradeId.isBlank() ? Optional.empty() : trades.findById(tradeId);
    }

    private Decision reject(TradeSubmission submission, Optional<StoredTrade> stored, List<Violation> violations) {
        outbox.append(events.rejected(submission, violations));
        return Decision.rejected(TradeRejectedException.of(submission, stored, violations));
    }

    /** The transaction's result: either a stored trade or a rejection to throw after commit. */
    private record Decision(IngestionResult result, TradeRejectedException rejection) {
        static Decision stored(IngestionResult result) {
            return new Decision(result, null);
        }

        static Decision rejected(TradeRejectedException rejection) {
            return new Decision(null, rejection);
        }

        Outcome outcome() {
            return rejection == null ? result.outcome() : Outcome.REJECTED;
        }

        List<ReasonCode> reasons() {
            return rejection == null ? List.of() : rejection.reasons();
        }
    }
}
