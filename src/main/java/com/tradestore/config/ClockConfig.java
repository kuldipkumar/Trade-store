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
        String zone = properties.timeZone();
        return Clock.system(zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone));
    }
}
