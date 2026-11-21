package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-604.2: outcome counters, tagged as in docs/05-lld.md section 8. */
class TradeMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TradeMetrics metrics = new TradeMetrics(registry);

    private double outcomes(String outcome, String reason, String channel) {
        Counter counter = registry.find("trade.outcomes")
                .tags("outcome", outcome, "reason", reason, "channel", channel).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @Tag("US-604.2")
    void should_countOneOutcomeWithReasonNone_when_tradeIsAccepted() {
        // Act
        metrics.recordOutcome(Outcome.ACCEPTED, List.of(), Channel.REST);

        // Assert
        assertThat(outcomes("ACCEPTED", "NONE", "REST")).isEqualTo(1.0);
    }
}
