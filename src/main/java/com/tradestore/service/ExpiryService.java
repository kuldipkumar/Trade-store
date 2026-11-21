package com.tradestore.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Marks matured trades as expired and records an EXPIRED outcome for each, in one transaction
 * (US-202, docs/05-lld.md section 4.2). Idempotent and safe to run on several instances: the UPDATE only
 * returns rows it changed.
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

    public ExpiryService(TradeRepository trades, OutboxRepository outbox, AuditEventFactory events,
                         TransactionOperations transactions, Clock clock, TradeMetrics metrics) {
        this.trades = trades;
        this.outbox = outbox;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** @return the number of trades newly marked expired */
    public int expireMaturedTrades() {
        LocalDate today = LocalDate.now(clock);
        List<Trade> expired = transactions.execute(status -> {
            List<Trade> changed = trades.markExpired(today);
            changed.forEach(trade -> outbox.append(events.expired(trade)));
            return changed;
        });
        for (Trade trade : expired) {
            metrics.recordOutcome(Outcome.EXPIRED, List.of(), Channel.SCHEDULER);
            log.info("outcome={} tradeId={} version={} channel={}", Outcome.EXPIRED, trade.tradeId(), trade.version(),
                    Channel.SCHEDULER);
        }
        return expired.size();
    }
}
