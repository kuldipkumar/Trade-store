package com.tradestore.domain;

/** No trade (or no history) is stored for this ID; mapped to 404 (US-402.2, US-404.2). */
public class TradeNotFoundException extends RuntimeException {

    private final String tradeId;

    public TradeNotFoundException(String tradeId) {
        super("No trade with ID " + tradeId);
        this.tradeId = tradeId;
    }

    public String tradeId() {
        return tradeId;
    }
}
