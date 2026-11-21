package com.tradestore.scheduler;

import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Stub: implemented test-first (ExpirySchedulerTest). */
@Component
public class ExpiryScheduler {

    private final ExpiryService expiry;

    public ExpiryScheduler(ExpiryService expiry, MeterRegistry registry) {
        this.expiry = expiry;
    }

    public void runScheduled() {
        expiry.expireMaturedTrades();
    }
}
