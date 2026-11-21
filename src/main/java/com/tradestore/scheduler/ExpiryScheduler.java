package com.tradestore.scheduler;

import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Stub: implemented test-first (ExpirySchedulerTest). */
@Component
public class ExpiryScheduler {

    public ExpiryScheduler(ExpiryService expiry, MeterRegistry registry) {
    }

    public void runScheduled() {
    }
}
