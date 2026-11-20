package com.tradestore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** US-205.1: "today" uses the configured zone, or the server's default zone when none is set (A-16). */
class ClockConfigTest {

    private final ClockConfig config = new ClockConfig();

    @Test
    void should_useServerDefaultZone_when_noZoneIsConfigured() {
        // Act + Assert
        assertThat(config.clock(new TradeStoreProperties(null, null)).getZone()).isEqualTo(ZoneId.systemDefault());
        assertThat(config.clock(new TradeStoreProperties("  ", null)).getZone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void should_useConfiguredZone_when_zoneIsSet() {
        // Act + Assert
        assertThat(config.clock(new TradeStoreProperties("Asia/Kolkata", null)).getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }
}
