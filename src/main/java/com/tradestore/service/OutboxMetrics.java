package com.tradestore.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.ToDoubleFunction;

import com.tradestore.persistence.OutboxBacklog;
import com.tradestore.persistence.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbox backlog gauges (A-46, docs/05-lld.md section 8): {@code trade_outbox_backlog_size} and
 * {@code trade_outbox_oldest_age_seconds}. A growing backlog or an old oldest event means the audit relay is
 * behind or failing. The backlog is read at most once per second, so one scrape gives two consistent values
 * from one query. A failed read gives NaN (the scrape still succeeds) and is logged with its cause.
 */
@Component
public class OutboxMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);
    private static final Duration MAX_AGE = Duration.ofSeconds(1);

    private final OutboxRepository outbox;
    private final Clock clock;
    private OutboxBacklog cached;
    private Instant cachedAt;

    public OutboxMetrics(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("trade.outbox.backlog.size", this, read(OutboxBacklog::size))
                .description("Outcomes waiting to be relayed to the audit trail")
                .register(registry);
        Gauge.builder("trade.outbox.oldest.age", this,
                        read(b -> b.oldest().map(o -> Duration.between(o, clock.instant()).toSeconds()).orElse(0L)))
                .baseUnit("seconds")
                .description("Age of the oldest outcome waiting to be relayed")
                .register(registry);
    }

    private ToDoubleFunction<OutboxMetrics> read(ToDoubleFunction<OutboxBacklog> value) {
        return self -> {
            OutboxBacklog backlog = backlog();
            return backlog == null ? Double.NaN : value.applyAsDouble(backlog);
        };
    }

    /** The backlog, read at most once per MAX_AGE; null when it could not be read. */
    private synchronized OutboxBacklog backlog() {
        Instant now = clock.instant();
        if (cachedAt == null || !now.isBefore(cachedAt.plus(MAX_AGE))) {
            cachedAt = now;
            try {
                cached = outbox.backlog();
            } catch (RuntimeException e) {
                cached = null;
                log.warn("Outbox backlog could not be read; backlog gauges report NaN: {}", e.toString());
            }
        }
        return cached;
    }
}
