-- The trace that wrote each outbox row, so the relay continues it when writing to MongoDB (ADR-012).
-- W3C traceparent: 00-<32 hex trace id>-<16 hex span id>-<2 hex flags> = 55 characters.
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(55);
