package com.tradestore.service;

import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import io.micrometer.core.instrument.MeterRegistry;

/** Stub: implemented test-first (TradeMetricsTest). */
public class TradeMetrics {

    private final MeterRegistry registry;

    public TradeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOutcome(Outcome outcome, List<ReasonCode> reasons, Channel channel) {
        registry.counter("trade.outcomes", "outcome", outcome.name(), "reason", "NONE", "channel", channel.name())
                .increment();
    }
}
