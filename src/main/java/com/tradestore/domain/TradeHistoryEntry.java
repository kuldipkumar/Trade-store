package com.tradestore.domain;

import java.time.Instant;
import java.time.LocalDate;

/** One accepted or replaced version of a trade, kept forever in the NoSQL history (R-02, US-404, A-31). */
public record TradeHistoryEntry(
        String eventId,
        String tradeId,
        int version,
        String counterPartyId,
        String bookId,
        LocalDate maturityDate,
        LocalDate createdDate,
        Outcome outcome,
        Channel channel,
        Instant receivedTimestamp) {

    /** @throws IllegalArgumentException if the event does not describe a stored trade */
    public static TradeHistoryEntry from(AuditEvent event) {
        Trade trade = event.trade();
        if (trade == null) {
            throw new IllegalArgumentException("Event " + event.eventId() + " (" + event.outcome() + ") has no trade");
        }
        return new TradeHistoryEntry(event.eventId(), trade.tradeId(), trade.version(), trade.counterPartyId(),
                trade.bookId(), trade.maturityDate(), trade.createdDate(), event.outcome(), event.channel(),
                event.eventTimestamp());
    }
}
