package com.tradestore.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The single source of "now" and "today" (AGENTS.md section 4: inject a Clock), in the configured zone. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock(TradeStoreProperties properties) {
        return Clock.system(zoneOf(properties));
    }

    /** The configured zone (A-16), or the server's default zone when none is set. */
    public static ZoneId zoneOf(TradeStoreProperties properties) {
        String zone = properties.timeZone();
        return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
    }
}
