package com.tradestore.domain;

/** Why a trade was rejected. A rejection can carry several reasons (A-15). */
public enum ReasonCode {
    INVALID_FIELD,
    LOWER_VERSION,
    MATURITY_IN_PAST
}
