package com.tradestore.persistence.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
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
}
