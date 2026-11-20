package com.tradestore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings under {@code trade-store.*} (A-50). Every value can be overridden by an environment
 * variable, for example TRADE_STORE_TIME_ZONE.
 *
 * @param timeZone the zone that defines "today" (A-16); empty means the server's default zone
 */
@ConfigurationProperties("trade-store")
public record TradeStoreProperties(String timeZone, @DefaultValue Audit audit) {

    public TradeStoreProperties {
        audit = audit == null ? new Audit(Audit.DEFAULT_MAX_PAYLOAD_CHARS) : audit;
    }

    /** @param maxPayloadChars longer payloads are truncated in the audit trail (US-302.2, A-52) */
    public record Audit(@DefaultValue("10000") int maxPayloadChars) {
        static final int DEFAULT_MAX_PAYLOAD_CHARS = 10_000;
    }
}
