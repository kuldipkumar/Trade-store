package com.tradestore.service;

import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeNotFoundException;
import com.tradestore.persistence.TradeRepository;
import org.springframework.stereotype.Service;

/** The read side for trades (US-402 to US-404). */
@Service
public class TradeQueryService {

    private final TradeRepository trades;

    public TradeQueryService(TradeRepository trades) {
        this.trades = trades;
    }

    /** @throws TradeNotFoundException if no trade is stored with this ID */
    public Trade get(String tradeId) {
        return trades.findTrade(tradeId).orElseThrow(() -> new TradeNotFoundException(tradeId));
    }
}
