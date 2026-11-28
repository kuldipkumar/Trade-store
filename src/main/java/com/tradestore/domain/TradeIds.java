package com.tradestore.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * What makes a string usable as a stored identifier (US-105.1, A-55): present, not blank, at most 64 characters (the
 * PostgreSQL columns), free of control characters (PostgreSQL rejects NUL outright) and valid Unicode (the driver
 * would store an unpaired surrogate as '?', so different IDs would collide). One rule set, used both by the field
 * validator and to decide whether an ID may be sent to the database as a key.
 */
public final class TradeIds {

    public static final int MAX_LENGTH = 64;

    private TradeIds() {
    }

    public static boolean isUsable(String id) {
        return problem("id", id).isEmpty();
    }

    /** Why {@code value} cannot be the stored identifier {@code field}, or empty when it can. */
    public static Optional<String> problem(String field, String value) {
        if (value == null || value.isBlank()) {
            return Optional.of(field + " is required");
        }
        if (value.length() > MAX_LENGTH) {
            return Optional.of(field + " must be at most " + MAX_LENGTH + " characters");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            return Optional.of(field + " must not contain control characters");
        }
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            return Optional.of(field + " must be valid Unicode text");
        }
        return Optional.empty();
    }
}
