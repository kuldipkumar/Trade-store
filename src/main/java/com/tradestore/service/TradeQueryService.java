package com.tradestore.service;

import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeNotFoundException;
import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.PageResult;
import com.tradestore.persistence.TradeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** The read side for trades (US-402 to US-404). */
@Service
public class TradeQueryService {

    private final TradeRepository trades;

    @Autowired
    public TradeQueryService(TradeRepository trades, TradeStoreProperties properties) {
        this(trades, properties.api().maxPageSize());
    }

    TradeQueryService(TradeRepository trades, int maxPageSize) {
        this.trades = trades;
    }

    /** Stub: implemented test-first. */
    public PageResult<Trade> list(Boolean expired, int page, int size) {
        return null;
    }

    /** @throws TradeNotFoundException if no trade is stored with this ID */
    public Trade get(String tradeId) {
        return trades.findTrade(tradeId).orElseThrow(() -> new TradeNotFoundException(tradeId));
    }
}
