package com.tradestore.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings under {@code trade-store.*} (A-50, docs/05-lld.md section 6.4). Every value can be
 * overridden by an environment variable, for example TRADE_STORE_TIME_ZONE.
 *
 * @param timeZone the zone that defines "today" (A-16); empty means the server's default zone
 */
@ConfigurationProperties("trade-store")
public record TradeStoreProperties(String timeZone, @DefaultValue Audit audit, @DefaultValue Outbox outbox,
                                   @DefaultValue Expiry expiry, @DefaultValue Api api) {

    public TradeStoreProperties {
        audit = audit == null ? new Audit(Audit.DEFAULT_MAX_PAYLOAD_CHARS) : audit;
        outbox = outbox == null
                ? new Outbox(Outbox.DEFAULT_POLL_INTERVAL, Outbox.DEFAULT_BATCH_SIZE, Outbox.DEFAULT_ALERT_AFTER_FAILED_POLLS)
                : outbox;
        expiry = expiry == null
                ? new Expiry(Expiry.DEFAULT_BATCH_SIZE, Expiry.DEFAULT_RUN_AT_STARTUP,
                        Expiry.DEFAULT_STARTUP_RETRY_INTERVAL, Expiry.DEFAULT_STARTUP_MAX_ATTEMPTS)
                : expiry;
        api = api == null ? new Api(Api.DEFAULT_MAX_REQUEST_BYTES) : api;
    }

    /** @param maxPayloadChars longer payloads are truncated in the audit trail (US-302.2, A-52) */
    public record Audit(@DefaultValue("10000") int maxPayloadChars) {
        static final int DEFAULT_MAX_PAYLOAD_CHARS = 10_000;
    }

    /**
     * @param pollInterval delay between relay runs; also read by OutboxRelayScheduler's @Scheduled
     * @param batchSize    maximum events relayed per run
     * @param alertAfterFailedPolls consecutive failed polls before the outage is logged at ERROR with the
     *                              affected trade IDs (US-501.3)
     */
    public record Outbox(@DefaultValue("1s") Duration pollInterval, @DefaultValue("500") int batchSize,
                         @DefaultValue("30") int alertAfterFailedPolls) {
        static final int DEFAULT_ALERT_AFTER_FAILED_POLLS = 30;

        public Outbox {
            if (alertAfterFailedPolls < 1) {
                throw new IllegalArgumentException(
                        "trade-store.outbox.alert-after-failed-polls must be at least 1, was " + alertAfterFailedPolls);
            }
        }
        static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
        static final int DEFAULT_BATCH_SIZE = 500;
    }

    /**
     * @param batchSize    maximum trades expired per transaction, so a large backlog (e.g. after downtime) never
     *                     locks every matured trade at once (US-202, US-203)
     * @param runAtStartup         run the expiry once when the application is ready, to catch up after downtime
     *                             (US-203)
     * @param startupRetryInterval wait between attempts when the startup run fails
     * @param startupMaxAttempts   startup attempts before giving up; the cron run remains the fallback
     */
    public record Expiry(@DefaultValue("1000") int batchSize, @DefaultValue("true") boolean runAtStartup,
                         @DefaultValue("1m") Duration startupRetryInterval, @DefaultValue("10") int startupMaxAttempts) {
        static final int DEFAULT_BATCH_SIZE = 1000;
        static final boolean DEFAULT_RUN_AT_STARTUP = true;
        static final Duration DEFAULT_STARTUP_RETRY_INTERVAL = Duration.ofMinutes(1);
        static final int DEFAULT_STARTUP_MAX_ATTEMPTS = 10;
    }

    /** @param maxRequestBytes larger request bodies are refused with 413 (US-401.6, A-47) */
    public record Api(@DefaultValue("1048576") long maxRequestBytes) {
        static final long DEFAULT_MAX_REQUEST_BYTES = 1_048_576L;
    }
}
