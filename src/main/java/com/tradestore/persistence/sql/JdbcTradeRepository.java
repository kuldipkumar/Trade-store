package com.tradestore.persistence.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
import com.tradestore.persistence.PageResult;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.persistence.UpsertResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL implementation with hand-written SQL (ADR-008). */
@Repository
public class JdbcTradeRepository implements TradeRepository {

    // xmax = 0 only for a freshly inserted row, which tells an insert from an update (docs/05-lld.md 4.1).
    private static final String UPSERT = """
            INSERT INTO trades (trade_id, version, counter_party_id, book_id, maturity_date, created_date, expired, updated_at)
            VALUES (:tradeId, :version, :counterPartyId, :bookId, :maturityDate, :createdDate, false, now())
            ON CONFLICT (trade_id) DO UPDATE
               SET version = EXCLUDED.version,
                   counter_party_id = EXCLUDED.counter_party_id,
                   book_id = EXCLUDED.book_id,
                   maturity_date = EXCLUDED.maturity_date,
                   created_date = EXCLUDED.created_date,
                   expired = false,
                   updated_at = now()
             WHERE trades.version <= EXCLUDED.version
            RETURNING (xmax = 0) AS inserted
            """;

    private static final String SELECT_TRADE = """
            SELECT trade_id, version, counter_party_id, book_id, maturity_date, created_date, expired
              FROM trades WHERE trade_id = :tradeId
            """;


    // One bounded batch (uses idx_trades_unexpired_maturity; "<" keeps a trade maturing today live, A-17).
    // Rows locked by another transaction (e.g. a concurrent replacement) are skipped and
    // picked up by a later batch or run, so expiry never waits on, or blocks, ingestion for long.
    private static final String MARK_EXPIRED_BATCH = """
            WITH due AS (
                SELECT trade_id FROM trades
                 WHERE maturity_date < :today AND expired = false
                 ORDER BY maturity_date, trade_id
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED)
            UPDATE trades t SET expired = true, updated_at = now()
              FROM due
             WHERE t.trade_id = due.trade_id
            RETURNING t.trade_id, t.version, t.counter_party_id, t.book_id, t.maturity_date, t.created_date, t.expired
            """;

    private final JdbcClient jdbc;

    public JdbcTradeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredTrade> findById(String tradeId) {
        return jdbc.sql("SELECT trade_id, version, expired FROM trades WHERE trade_id = :tradeId")
                .param("tradeId", tradeId)
                .query((rs, n) -> new StoredTrade(rs.getString("trade_id"), rs.getInt("version"), rs.getBoolean("expired")))
                .optional();
    }

    @Override
    public Optional<Trade> findTrade(String tradeId) {
        return jdbc.sql(SELECT_TRADE).param("tradeId", tradeId).query(JdbcTradeRepository::mapTrade).optional();
    }

    @Override
    public UpsertResult upsertIfNotOlder(Trade trade) {
        return jdbc.sql(UPSERT)
                .param("tradeId", trade.tradeId())
                .param("version", trade.version())
                .param("counterPartyId", trade.counterPartyId())
                .param("bookId", trade.bookId())
                .param("maturityDate", trade.maturityDate())
                .param("createdDate", trade.createdDate())
                .query((rs, n) -> rs.getBoolean("inserted"))
                .optional()
                .map(inserted -> inserted ? UpsertResult.INSERTED : UpsertResult.UPDATED)
                .orElse(UpsertResult.STALE);
    }

    static Trade mapTrade(ResultSet rs, int rowNum) throws SQLException {
        return new Trade(
                rs.getString("trade_id"),
                rs.getInt("version"),
                rs.getString("counter_party_id"),
                rs.getString("book_id"),
                rs.getObject("maturity_date", java.time.LocalDate.class),
                rs.getObject("created_date", java.time.LocalDate.class),
                rs.getBoolean("expired"));
    }


    @Override
    public List<Trade> markExpired(LocalDate today, int limit) {
        return jdbc.sql(MARK_EXPIRED_BATCH).param("today", today).param("limit", limit)
                .query(JdbcTradeRepository::mapTrade).list();
    }

    @Override
    public PageResult<Trade> findPage(Boolean expired, int page, int size) {
        // ":expired IS NULL" makes the filter optional. ORDER BY trade_id COLLATE "C": stable, locale-independent
        // (code-point) order on every PostgreSQL installation. count(*) OVER () returns the total from the same
        // statement as the page, so the two always agree (US-403).
        String where = " WHERE (CAST(:expired AS boolean) IS NULL OR expired = :expired)";
        List<Counted> rows = jdbc.sql("""
                        SELECT trade_id, version, counter_party_id, book_id, maturity_date, created_date, expired,
                               count(*) OVER () AS total
                          FROM trades""" + where + " ORDER BY trade_id COLLATE \"C\" LIMIT :size OFFSET :offset")
                .param("expired", expired)
                .param("size", size)
                .param("offset", (long) page * size)
                .query((rs, n) -> new Counted(mapTrade(rs, n), rs.getLong("total")))
                .list();
        long total = rows.isEmpty()
                ? jdbc.sql("SELECT count(*) FROM trades" + where).param("expired", expired).query(Long.class).single()
                : rows.getFirst().total();
        return new PageResult<>(rows.stream().map(Counted::trade).toList(), page, size, total);
    }

    private record Counted(Trade trade, long total) {
    }
}
