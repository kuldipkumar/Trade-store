package com.tradestore.persistence;

/** The result of a version-guarded write. */
public enum UpsertResult {
    /** No trade was stored; the new trade was inserted. */
    INSERTED,
    /** A trade with the same or a lower version was replaced. */
    UPDATED,
    /** A higher version is stored (written concurrently); nothing was changed. */
    STALE
}
