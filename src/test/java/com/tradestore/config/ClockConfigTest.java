package com.tradestore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** US-205.1: "today" uses the configured zone, or the server's default zone when none is set (A-16). */
class ClockConfigTest {

    private final ClockConfig config = new ClockConfig();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    @Tag("US-205.1")
    void should_useServerDefaultZone_when_noZoneIsConfigured(String zone) {
        // Act
        ZoneId actual = config.clock(new TradeStoreProperties(zone, null, null, null, null, null)).getZone();

        // Assert
        assertThat(actual).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    @Tag("US-205.1")
    void should_useConfiguredZone_when_zoneIsSet() {
        // Act + Assert
        assertThat(config.clock(new TradeStoreProperties("Asia/Kolkata", null, null, null, null, null)).getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    }
}
