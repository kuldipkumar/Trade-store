package com.tradestore.service;

import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Stub: implemented test-first (TradeMetricsTest). */
public class TradeMetrics {

    private final MeterRegistry registry;

    public TradeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOutcome(Outcome outcome, List<ReasonCode> reasons, Channel channel) {
        List<String> tags = reasons.isEmpty() ? List.of("NONE") : reasons.stream().map(Enum::name).toList();
        for (String reason : tags) {
            registry.counter("trade.outcomes", "outcome", outcome.name(), "reason", reason, "channel", channel.name())
                    .increment();
        }
    }

    /** Starts measuring one ingestion; uses the registry's monotonic clock, not wall time. */
    public Timer.Sample startProcessing() {
        return Timer.start(registry);
    }

    /** Ends a {@link #startProcessing()} measurement: the trade.processing timer, tagged by channel. */
    public void stopProcessing(Timer.Sample sample, Channel channel) {
        sample.stop(registry.timer("trade.processing", "channel", channel.name()));
    }
}
