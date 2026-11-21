package com.tradestore.api;

import java.time.LocalDate;

import com.tradestore.domain.Trade;

/** A stored trade, as returned by the API (docs/05-lld.md section 6.1). */
public record TradeResponse(String tradeId, int version, String counterPartyId, String bookId, LocalDate maturityDate,
                            LocalDate createdDate, boolean expired) {

    public static TradeResponse from(Trade trade) {
        return new TradeResponse(trade.tradeId(), trade.version(), trade.counterPartyId(), trade.bookId(),
                trade.maturityDate(), trade.createdDate(), trade.expired());
    }
}
