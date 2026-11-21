package com.tradestore.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertThat(properties.outbox().alertAfterFailedPolls()).isEqualTo(30);
        assertThat(properties.expiry().batchSize()).isEqualTo(1000);
        assertThat(properties.expiry().runAtStartup()).isTrue();
        assertThat(properties.api().maxRequestBytes()).isEqualTo(1_048_576L);
        assertThat(properties.expiry().startupRetryInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.expiry().startupMaxAttempts()).isEqualTo(10);
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
                "trade-store.outbox.alert-after-failed-polls", "5",
                "trade-store.expiry.batch-size", "200",
                "trade-store.expiry.run-at-startup", "false",
                "trade-store.api.max-request-bytes", "2048",
                "trade-store.expiry.startup-retry-interval", "30s",
                "trade-store.expiry.startup-max-attempts", "3"));

        // Assert
        assertThat(properties.timeZone()).isEqualTo("Asia/Kolkata");
        assertThat(properties.audit().maxPayloadChars()).isEqualTo(256);
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.outbox().batchSize()).isEqualTo(50);
        assertThat(properties.outbox().alertAfterFailedPolls()).isEqualTo(5);
        assertThat(properties.expiry().batchSize()).isEqualTo(200);
        assertThat(properties.expiry().runAtStartup()).isFalse();
        assertThat(properties.api().maxRequestBytes()).isEqualTo(2048L);
        assertThat(properties.expiry().startupRetryInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.expiry().startupMaxAttempts()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @Tag("US-501.3")
    void should_failAtStartup_when_alertAfterFailedPollsIsBelowOne(String value) {
        // Act + Assert: a fail-fast startup error instead of a division by zero on the first failed poll
        assertThatThrownBy(() -> bind(Map.of("trade-store.outbox.alert-after-failed-polls", value)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("alert-after-failed-polls must be at least 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    @Tag("US-401.6")
    void should_failAtStartup_when_maxRequestBytesIsNotPositive(String value) {
        // Act + Assert: otherwise every non-empty body (or every body) would get 413
        assertThatThrownBy(() -> bind(Map.of("trade-store.api.max-request-bytes", value)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("max-request-bytes must be positive");
    }
}
