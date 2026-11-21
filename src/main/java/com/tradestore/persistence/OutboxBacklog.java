package com.tradestore.persistence;

import java.time.Instant;
import java.util.Optional;

/**
 * How many outcomes wait in the outbox, and when the oldest was recorded (A-46).
 *
 * @param oldest empty when the outbox is empty
 */
public record OutboxBacklog(long size, Optional<Instant> oldest) {
}
