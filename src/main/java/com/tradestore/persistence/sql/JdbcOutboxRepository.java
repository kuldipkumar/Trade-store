package com.tradestore.persistence.sql;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.persistence.OutboxBacklog;
import com.tradestore.observability.TraceContext;
import com.tradestore.persistence.OutboxEntry;
import com.tradestore.persistence.OutboxRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** PostgreSQL outbox; each event is stored as JSONB (ADR-005, ADR-008). */
@Repository
public class JdbcOutboxRepository implements OutboxRepository {

    private static final String INSERT = """
            INSERT INTO outbox_events (event_id, trade_id, outcome, payload, created_at, traceparent)
            VALUES (:eventId, :tradeId, :outcome, CAST(:payload AS jsonb), :createdAt, :traceparent)
            """;

    private static final String CLAIM = """
            SELECT payload, traceparent FROM outbox_events ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TraceContext trace;

    public JdbcOutboxRepository(JdbcClient jdbc, JsonMapper json, TraceContext trace) {
        this.jdbc = jdbc;
        this.json = json;
        this.trace = trace;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql(INSERT)
                .param("eventId", UUID.fromString(event.eventId()))
                .param("tradeId", event.tradeId())
                .param("outcome", event.outcome().name())
                .param("payload", json.writeValueAsString(event))
                .param("createdAt", Timestamp.from(event.eventTimestamp()))
                .param("traceparent", trace.currentTraceparent())
                .update();
    }

    @Override
    public List<OutboxEntry> claimBatch(int limit) {
        return jdbc.sql(CLAIM)
                .param("limit", limit)
                .query((rs, n) -> new OutboxEntry(json.readValue(rs.getString("payload"), AuditEvent.class),
                        rs.getString("traceparent")))
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
        return jdbc.sql("""
                        SELECT trade_id FROM outbox_events WHERE trade_id IS NOT NULL
                         GROUP BY trade_id ORDER BY min(id) LIMIT :limit
                        """)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    @Override
    public OutboxBacklog backlog() {
        return jdbc.sql("SELECT count(*) AS size, min(created_at) AS oldest FROM outbox_events")
                .query((rs, n) -> new OutboxBacklog(rs.getLong("size"),
                        Optional.ofNullable(rs.getTimestamp("oldest")).map(Timestamp::toInstant)))
                .single();
    }
}
