package com.tradestore.domain;

/** The stored state that validation needs: the latest version of a trade. */
public record StoredTrade(String tradeId, int version, boolean expired) {
}
