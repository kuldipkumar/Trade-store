package com.tradestore.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Marks matured trades as expired and records an EXPIRED outcome for each (US-202, docs/05-lld.md section 4.2).
 * Works in bounded batches, one transaction per batch, so a large backlog (e.g. the startup catch-up after
 * downtime, US-203) never locks every matured trade at once. Idempotent and safe on several instances: each
 * batch only returns rows it changed and skips rows locked by another transaction.
 */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    private final TradeRepository trades;
    private final OutboxRepository outbox;
    private final AuditEventFactory events;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final TradeMetrics metrics;
    private final int batchSize;

    @Autowired
    public ExpiryService(TradeRepository trades, OutboxRepository outbox, AuditEventFactory events,
                         TransactionOperations transactions, Clock clock, TradeMetrics metrics,
                         TradeStoreProperties properties) {
        this(trades, outbox, events, transactions, clock, metrics, properties.expiry().batchSize());
    }

    ExpiryService(TradeRepository trades, OutboxRepository outbox, AuditEventFactory events,
                  TransactionOperations transactions, Clock clock, TradeMetrics metrics, int batchSize) {
        this.trades = trades;
        this.outbox = outbox;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    /** @return the number of trades newly marked expired */
    public int expireMaturedTrades() {
        LocalDate today = LocalDate.now(clock);
        int total = 0;
        List<Trade> batch;
        do {
            batch = transactions.execute(status -> {
                List<Trade> changed = trades.markExpired(today, batchSize);
                changed.forEach(trade -> outbox.append(events.expired(trade)));
                return changed;
            });
            for (Trade trade : batch) {
                metrics.recordOutcome(Outcome.EXPIRED, List.of(), Channel.SCHEDULER);
                log.info("outcome={} tradeId={} version={} channel={}", Outcome.EXPIRED, trade.tradeId(),
                        trade.version(), Channel.SCHEDULER);
            }
            total += batch.size();
        } while (batch.size() == batchSize); // a full batch means there may be more
        return total;
    }
}
