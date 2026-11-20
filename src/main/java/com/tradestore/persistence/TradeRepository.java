package com.tradestore.persistence;

import java.util.Optional;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;

/** The latest version of each trade, in the SQL system of record (A-36). */
public interface TradeRepository {

    /** The fields validation needs, or empty if the trade is not stored. */
    Optional<StoredTrade> findById(String tradeId);

    Optional<Trade> findTrade(String tradeId);

    /**
     * Inserts the trade, or replaces the stored one if its version is the same or lower, in one atomic
     * statement (ADR-003). Returns {@link UpsertResult#STALE} without writing if a higher version is stored.
     */
    UpsertResult upsertIfNotOlder(Trade trade);
}
