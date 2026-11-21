package com.tradestore.service;

import java.time.Clock;

import com.tradestore.persistence.OutboxRepository;
import com.tradestore.persistence.TradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/** Stub: implemented test-first (ExpiryIT, ExpiryServiceTest). */
@Service
public class ExpiryService {

    public ExpiryService(TradeRepository trades, OutboxRepository outbox, AuditEventFactory events,
                         TransactionOperations transactions, Clock clock, TradeMetrics metrics) {
    }

    /** @return the number of trades newly marked expired */
    public int expireMaturedTrades() {
        return 0;
    }
}
