package com.tradestore.scheduler;

import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the expiry job (US-202). A failed run is counted ({@code trade_job_failures_total{job="expiry"}}) and
 * logged at ERROR, never thrown: the next run, or the run at startup (US-203), catches up.
 */
@Component
public class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final ExpiryService expiry;
    private final Counter failures;

    public ExpiryScheduler(ExpiryService expiry, MeterRegistry registry) {
        this.expiry = expiry;
        this.failures = registry.counter("trade.job.failures", "job", "expiry");
    }

    public void runScheduled() {
        try {
            expiry.expireMaturedTrades();
        } catch (RuntimeException e) {
            failures.increment();
            log.error("Expiry run failed; the next run catches up: {}", e.toString());
        }
    }
}
