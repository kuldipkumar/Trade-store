package com.tradestore.persistence;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * Marks every unexpired trade whose maturity date is before {@code today} as expired, in one statement,
     * and returns the trades it changed (A-17, US-202). Already-expired trades are never returned, so running
     * it again changes nothing (idempotent, US-202.2). It never sets expired back to false (US-204.2).
     */
    List<Trade> markExpired(LocalDate today);
}
