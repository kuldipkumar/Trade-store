package com.tradestore.domain;

/** What happened to a trade or message; one audit entry per outcome (A-29). */
public enum Outcome {
    ACCEPTED,
    REPLACED,
    REJECTED,
    EXPIRED,
    POISON,
    FAILED
}
