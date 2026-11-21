package com.tradestore.api;

import java.time.Instant;
import java.time.LocalDate;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.TradeHistoryEntry;

/** One accepted version of a trade, as returned by the history endpoint (docs/05-lld.md section 6.1). */
public record TradeVersionResponse(String tradeId, int version, String counterPartyId, String bookId,
                                   LocalDate maturityDate, LocalDate createdDate, Outcome outcome, Channel channel,
                                   Instant receivedTimestamp) {

    public static TradeVersionResponse from(TradeHistoryEntry e) {
        return new TradeVersionResponse(e.tradeId(), e.version(), e.counterPartyId(), e.bookId(), e.maturityDate(),
                e.createdDate(), e.outcome(), e.channel(), e.receivedTimestamp());
    }
}
