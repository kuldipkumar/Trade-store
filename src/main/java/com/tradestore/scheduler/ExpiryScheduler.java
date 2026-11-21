package com.tradestore.scheduler;

import java.time.Duration;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
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
    private final boolean runAtStartup;
    private final TaskScheduler taskScheduler;
    private final Duration startupRetryInterval;
    private final int startupMaxAttempts;

    @Autowired
    public ExpiryScheduler(ExpiryService expiry, MeterRegistry registry, TaskScheduler taskScheduler,
                           TradeStoreProperties properties) {
        this(expiry, registry, taskScheduler, properties.expiry().runAtStartup(),
                properties.expiry().startupRetryInterval(), properties.expiry().startupMaxAttempts());
    }

    ExpiryScheduler(ExpiryService expiry, MeterRegistry registry, TaskScheduler taskScheduler, boolean runAtStartup,
                    Duration startupRetryInterval, int startupMaxAttempts) {
        this.expiry = expiry;
        this.failures = registry.counter("trade.job.failures", "job", "expiry");
        this.runAtStartup = runAtStartup;
        this.taskScheduler = taskScheduler;
        this.startupRetryInterval = startupRetryInterval;
        this.startupMaxAttempts = startupMaxAttempts;
    }

    /** Catches up once at startup, so trades that matured while the service was down are not left stale (US-203). */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (runAtStartup) {
            // Off the startup thread: Spring reports readiness only after ready-event listeners return.
            taskScheduler.schedule(() -> startupAttempt(1), taskScheduler.getClock().instant());
        }
    }

    /** Default: daily at 00:05 in the configured zone, or the server's zone if none is set (A-18, US-205). */
    @Scheduled(cron = "${trade-store.expiry.cron:0 5 0 * * *}", zone = "${trade-store.time-zone:}")
    public void runScheduled() {
        runOnce();
    }

    private void startupAttempt(int attempt) {
        if (runOnce()) {
            return;
        }
        if (attempt >= startupMaxAttempts) {
            log.error("Startup expiry catch-up gave up after {} attempts; the scheduled run will catch up", attempt);
            return;
        }
        taskScheduler.schedule(() -> startupAttempt(attempt + 1),
                taskScheduler.getClock().instant().plus(startupRetryInterval));
    }

    /** @return true if the run succeeded */
    private boolean runOnce() {
        try {
            int expired = expiry.expireMaturedTrades();
            log.info("Expiry run expired {} trades", expired);
            return true;
        } catch (RuntimeException e) {
            failures.increment();
            log.error("Expiry run failed; the next run catches up", e);
            return false;
        }
    }
}
