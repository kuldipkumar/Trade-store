package com.tradestore.service;

import java.time.Clock;

import com.tradestore.persistence.OutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/** Stub: implemented test-first (OutboxMetricsTest). */
public class OutboxMetrics implements MeterBinder {

    public OutboxMetrics(OutboxRepository outbox, Clock clock) {
    }

    @Override
    public void bindTo(MeterRegistry registry) {
    }
}
