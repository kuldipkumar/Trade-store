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
                                   @DefaultValue Expiry expiry) {

    public TradeStoreProperties {
        audit = audit == null ? new Audit(Audit.DEFAULT_MAX_PAYLOAD_CHARS) : audit;
        outbox = outbox == null ? new Outbox(Outbox.DEFAULT_POLL_INTERVAL, Outbox.DEFAULT_BATCH_SIZE) : outbox;
        expiry = expiry == null ? new Expiry(Expiry.DEFAULT_BATCH_SIZE, false) : expiry; // runAtStartup: stub default
    }

    /** @param maxPayloadChars longer payloads are truncated in the audit trail (US-302.2, A-52) */
    public record Audit(@DefaultValue("10000") int maxPayloadChars) {
        static final int DEFAULT_MAX_PAYLOAD_CHARS = 10_000;
    }

    /**
     * @param pollInterval delay between relay runs; also read by OutboxRelayScheduler's @Scheduled
     * @param batchSize    maximum events relayed per run
     */
    public record Outbox(@DefaultValue("1s") Duration pollInterval, @DefaultValue("500") int batchSize) {
        static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(1);
        static final int DEFAULT_BATCH_SIZE = 500;
    }

    /**
     * @param batchSize maximum trades expired per transaction, so a large backlog (e.g. after downtime) never
     *                  locks every matured trade at once (US-202, US-203)
     */
    public record Expiry(@DefaultValue("1000") int batchSize, boolean runAtStartup) {
        static final int DEFAULT_BATCH_SIZE = 1000;
    }
}
