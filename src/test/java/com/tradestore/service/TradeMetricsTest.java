package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
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

    @Test
    @Tag("US-604.2")
    void should_countOncePerReason_when_tradeIsRejectedForSeveralReasons() {
        // Act
        metrics.recordOutcome(Outcome.REJECTED, List.of(ReasonCode.LOWER_VERSION, ReasonCode.MATURITY_IN_PAST), Channel.STREAM);

        // Assert ("rejections by reason", US-604.2)
        assertThat(outcomes("REJECTED", "LOWER_VERSION", "STREAM")).isEqualTo(1.0);
        assertThat(outcomes("REJECTED", "MATURITY_IN_PAST", "STREAM")).isEqualTo(1.0);
        assertThat(outcomes("REJECTED", "NONE", "STREAM")).isZero();
    }

    @Test
    @Tag("US-604.2")
    void should_recordProcessingTimePerChannel_when_processingStops() {
        // Arrange
        MockClock clock = new MockClock();
        SimpleMeterRegistry timedRegistry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        TradeMetrics timedMetrics = new TradeMetrics(timedRegistry);
        Timer.Sample sample = timedMetrics.startProcessing();
        clock.add(Duration.ofMillis(25));

        // Act
        timedMetrics.stopProcessing(sample, Channel.STREAM);

        // Assert ("a processing-latency timer", US-604.2)
        Timer timer = timedRegistry.find("trade.processing").tag("channel", "STREAM").timer();
        assertThat(timer == null ? 0 : timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25.0);
    }
}
