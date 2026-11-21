package com.tradestore.scheduler;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Autowired
    public ExpiryScheduler(ExpiryService expiry, MeterRegistry registry, TradeStoreProperties properties) {
        this(expiry, registry, properties.expiry().runAtStartup());
    }

    ExpiryScheduler(ExpiryService expiry, MeterRegistry registry, boolean runAtStartup) {
        this.expiry = expiry;
        this.failures = registry.counter("trade.job.failures", "job", "expiry");
    }

    /** Catches up once at startup, so trades that matured while the service was down are not left stale (US-203). */
    public void onApplicationReady() {
        runScheduled();
    }

    /** Default: daily at 00:05 in the configured zone, or the server's zone if none is set (A-18, US-205). */
    @Scheduled(cron = "${trade-store.expiry.cron:0 5 0 * * *}", zone = "${trade-store.time-zone:}")
    public void runScheduled() {
        try {
            int expired = expiry.expireMaturedTrades();
            log.info("Expiry run expired {} trades", expired);
        } catch (RuntimeException e) {
            failures.increment();
            log.error("Expiry run failed; the next run catches up", e);
        }
    }
}
