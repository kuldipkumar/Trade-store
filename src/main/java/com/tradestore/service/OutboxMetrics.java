package com.tradestore.service;

import java.time.Clock;
import java.time.Duration;
import java.util.function.ToDoubleFunction;

import com.tradestore.persistence.OutboxBacklog;
import com.tradestore.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog gauges (A-46, docs/05-lld.md section 8): {@code trade_outbox_backlog_size} and
 * {@code trade_outbox_oldest_age_seconds}. A growing backlog or an old oldest event means the audit relay is
 * behind or failing. Read on each scrape; a database error gives NaN instead of failing the scrape.
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxMetrics(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("trade.outbox.backlog.size", this, safely(b -> b.size()))
                .description("Outcomes waiting to be relayed to the audit trail")
                .register(registry);
        Gauge.builder("trade.outbox.oldest.age", this,
                        safely(b -> b.oldest().map(o -> Duration.between(o, clock.instant()).toSeconds()).orElse(0L)))
                .baseUnit("seconds")
                .description("Age of the oldest outcome waiting to be relayed")
                .register(registry);
    }

    private ToDoubleFunction<OutboxMetrics> safely(ToDoubleFunction<OutboxBacklog> read) {
        return self -> {
            try {
                return read.applyAsDouble(outbox.backlog());
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        };
    }
}
