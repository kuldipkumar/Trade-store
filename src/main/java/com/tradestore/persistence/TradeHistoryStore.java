package com.tradestore.persistence;

import java.util.List;

import com.tradestore.domain.TradeHistoryEntry;

/** Every accepted version of every trade (R-02). Append-only. */
public interface TradeHistoryStore {

    /** Inserts the entry, keyed by its eventId. Inserting the same entry again is a no-op (idempotent). */
    void insert(TradeHistoryEntry entry);

    /** All versions of a trade, ordered by version and then by received time (US-404.1). */
    List<TradeHistoryEntry> findByTradeId(String tradeId);
}
