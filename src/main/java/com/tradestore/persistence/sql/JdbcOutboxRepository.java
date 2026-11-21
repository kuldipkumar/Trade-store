package com.tradestore.persistence.sql;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.persistence.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL outbox; each event is stored as JSONB (ADR-005, ADR-008). */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String INSERT = """
            INSERT INTO outbox_events (event_id, trade_id, outcome, payload, created_at)
            VALUES (:eventId, :tradeId, :outcome, CAST(:payload AS jsonb), :createdAt)
            """;

    private static final String CLAIM = """
            SELECT payload FROM outbox_events ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcOutboxRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql(INSERT)
                .param("eventId", UUID.fromString(event.eventId()))
                .param("tradeId", event.tradeId())
                .param("outcome", event.outcome().name())
                .param("payload", json.writeValueAsString(event))
                .param("createdAt", Timestamp.from(event.eventTimestamp()))
                .update();
    }

    @Override
    public List<AuditEvent> claimBatch(int limit) {
        return jdbc.sql(CLAIM)
                .param("limit", limit)
                .query((rs, n) -> json.readValue(rs.getString("payload"), AuditEvent.class))
                .list();
    }

    @Override
    public void delete(List<String> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM outbox_events WHERE event_id IN (:ids)")
                .param("ids", eventIds.stream().map(UUID::fromString).toList())
                .update();
    }

    @Override
    public List<String> pendingTradeIds(int limit) {
        return List.of(); // stub: implemented test-first
    }
}
