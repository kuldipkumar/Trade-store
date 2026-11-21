package com.tradestore.service;

import java.util.List;

import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeHistoryEntry;
import com.tradestore.domain.TradeNotFoundException;
import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.PageResult;
import com.tradestore.persistence.TradeHistoryStore;
import com.tradestore.persistence.TradeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** The read side for trades (US-402 to US-404). */
@Service
public class TradeQueryService {

    private final TradeRepository trades;
    private final int maxPageSize;

    @Autowired
    public TradeQueryService(TradeRepository trades, TradeHistoryStore history, TradeStoreProperties properties) {
        this(trades, history, properties.api().maxPageSize());
    }

    TradeQueryService(TradeRepository trades, TradeHistoryStore history, int maxPageSize) {
        this.trades = trades;
        this.maxPageSize = maxPageSize;
    }

    /** One page of trades, sorted by tradeId; the size is capped at max-page-size (US-403, A-52). */
    public PageResult<Trade> list(Boolean expired, int page, int size) {
        return trades.findPage(expired, page, Math.min(size, maxPageSize));
    }

    /** Stub: implemented test-first. */
    public List<TradeHistoryEntry> history(String tradeId) {
        return null;
    }

    /** @throws TradeNotFoundException if no trade is stored with this ID */
    public Trade get(String tradeId) {
        return trades.findTrade(tradeId).orElseThrow(() -> new TradeNotFoundException(tradeId));
    }
}
