package com.tradestore.domain;

/** Where an outcome came from. SCHEDULER is used for expiry events (docs/05-lld.md D4). */
public enum Channel {
    STREAM,
    REST,
    SCHEDULER
}
