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
     * Marks up to {@code limit} unexpired trades whose maturity date is before {@code today} as expired, and
     * returns the trades it changed (A-17, US-202). Rows locked by another transaction are skipped. Already
     * expired trades are never returned, so repeating it changes nothing (US-202.2), and it never sets expired
     * back to false (US-204.2). Callers repeat it while it returns a full batch.
     */
    List<Trade> markExpired(LocalDate today, int limit);

    /** One page of trades sorted by tradeId, optionally only expired or only live ones (null = all) (US-403). */
    PageResult<Trade> findPage(Boolean expired, int page, int size);
}
