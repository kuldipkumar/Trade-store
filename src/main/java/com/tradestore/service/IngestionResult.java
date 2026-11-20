package com.tradestore.service;

import com.tradestore.domain.Outcome;
import com.tradestore.domain.Trade;

/** A stored trade and whether it was new (ACCEPTED) or replaced an existing version (REPLACED). */
public record IngestionResult(Outcome outcome, Trade trade) {
}
