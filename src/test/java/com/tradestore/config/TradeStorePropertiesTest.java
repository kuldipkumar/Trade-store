package com.tradestore.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** A-50: every setting is externalised, with the defaults documented in docs/05-lld.md section 6.4. */
class TradeStorePropertiesTest {

    private static TradeStoreProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("trade-store", TradeStoreProperties.class);
    }

    @Test
    @Tag("A-50")
    void should_useDocumentedDefaults_when_nothingIsConfigured() {
        // Act
        TradeStoreProperties properties = bind(Map.of());

        // Assert
        assertThat(properties.timeZone()).isNull();
        assertThat(properties.audit().maxPayloadChars()).isEqualTo(10_000);
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.outbox().batchSize()).isEqualTo(500);
        assertThat(properties.expiry().batchSize()).isEqualTo(1000);
        assertThat(properties.expiry().runAtStartup()).isTrue();
    }

    @Test
    @Tag("A-50")
    void should_useConfiguredValues_when_propertiesAreSet() {
        // Act
        TradeStoreProperties properties = bind(Map.of(
                "trade-store.time-zone", "Asia/Kolkata",
                "trade-store.audit.max-payload-chars", "256",
                "trade-store.outbox.poll-interval", "250ms",
                "trade-store.outbox.batch-size", "50",
                "trade-store.expiry.batch-size", "200",
                "trade-store.expiry.run-at-startup", "false"));

        // Assert
        assertThat(properties.timeZone()).isEqualTo("Asia/Kolkata");
        assertThat(properties.audit().maxPayloadChars()).isEqualTo(256);
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.outbox().batchSize()).isEqualTo(50);
        assertThat(properties.expiry().batchSize()).isEqualTo(200);
        assertThat(properties.expiry().runAtStartup()).isFalse();
    }
}
