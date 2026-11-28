package com.tradestore.domain;

/**
 * What makes a string usable as a stored identifier (US-105.1): present, not blank, at most 64 characters (the
 * PostgreSQL columns) and free of control characters (PostgreSQL rejects NUL outright). An identifier that fails
 * this is rejected as {@code INVALID_FIELD} and is never sent to the database as a key.
 */
public final class TradeIds {

    public static final int MAX_LENGTH = 64;

    private TradeIds() {
    }

    public static boolean isUsable(String id) {
        return id != null && !id.isBlank() && id.length() <= MAX_LENGTH && id.chars().noneMatch(Character::isISOControl);
    }
}
