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
        assertThat(properties.api().maxPageSize()).isEqualTo(100);
        assertThat(properties.kafka().topic()).isEqualTo("trades.inbound");
        assertThat(properties.kafka().partitions()).isEqualTo(6);
        assertThat(properties.kafka().replicationFactor()).isEqualTo((short) 1);
        assertThat(properties.expiry().startupRetryInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.expiry().startupMaxAttempts()).isEqualTo(10);
    }

    @Test
    @Tag("US-302.3")
    @Tag("A-50")
    void should_defaultTheDeadLetterTopicAndAllowItToBeConfigured_when_bound() {
        // Act
        TradeStoreProperties defaults = bind(Map.of());
        TradeStoreProperties configured = bind(Map.of("trade-store.kafka.dlt-topic", "trades.dead"));

        // Assert
        assertThat(defaults.kafka().dltTopic()).isEqualTo("trades.inbound.DLT");
        assertThat(configured.kafka().dltTopic()).isEqualTo("trades.dead");
    }

    @Test
    @Tag("US-302.3")
    @Tag("A-50")
    void should_deriveTheDeadLetterTopicFromTheInboundTopic_when_onlyTheTopicIsConfigured() {
        // Act
        TradeStoreProperties properties = bind(Map.of("trade-store.kafka.topic", "orders.trades"));

        // Assert: never shares another deployment's dead letters (code review of PR #61)
        assertThat(properties.kafka().dltTopic()).isEqualTo("orders.trades.DLT");
    }

    @Test
    @Tag("US-303.3")
    void should_useTheDocumentedRetryDefaultsAndConfiguredValues_when_bound() {
        // Act
        TradeStoreProperties.Retry defaults = bind(Map.of()).retry();
        TradeStoreProperties.Retry configured = bind(Map.of("trade-store.retry.max-attempts", "5",
                "trade-store.retry.initial-interval", "200ms", "trade-store.retry.multiplier", "3",
                "trade-store.retry.max-interval", "30s")).retry();

        // Assert
        assertThat(defaults).isEqualTo(new TradeStoreProperties.Retry(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10)));
        assertThat(configured).isEqualTo(new TradeStoreProperties.Retry(5, Duration.ofMillis(200), 3.0, Duration.ofSeconds(30)));
    }

    @Test
    @Tag("A-50")
    void should_useConfiguredValues_when_propertiesAreSet() {
        // Act
        TradeStoreProperties properties = bind(Map.ofEntries(
                Map.entry("trade-store.time-zone", "Asia/Kolkata"),
                Map.entry("trade-store.audit.max-payload-chars", "256"),
                Map.entry("trade-store.outbox.poll-interval", "250ms"),
                Map.entry("trade-store.outbox.batch-size", "50"),
                Map.entry("trade-store.outbox.alert-after-failed-polls", "5"),
                Map.entry("trade-store.expiry.batch-size", "200"),
                Map.entry("trade-store.expiry.run-at-startup", "false"),
                Map.entry("trade-store.api.max-request-bytes", "2048"),
                Map.entry("trade-store.api.max-page-size", "25"),
                Map.entry("trade-store.expiry.startup-retry-interval", "30s"),
                Map.entry("trade-store.expiry.startup-max-attempts", "3")));

        // Assert
        assertThat(properties.timeZone()).isEqualTo("Asia/Kolkata");
        assertThat(properties.audit().maxPayloadChars()).isEqualTo(256);
        assertThat(properties.outbox().pollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.outbox().batchSize()).isEqualTo(50);
        assertThat(properties.outbox().alertAfterFailedPolls()).isEqualTo(5);
        assertThat(properties.expiry().batchSize()).isEqualTo(200);
        assertThat(properties.expiry().runAtStartup()).isFalse();
        assertThat(properties.api().maxRequestBytes()).isEqualTo(2048L);
        assertThat(properties.api().maxPageSize()).isEqualTo(25);
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

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5"})
    @Tag("US-403.1")
    void should_failAtStartup_when_maxPageSizeIsBelowOne(String value) {
        // Act + Assert: otherwise every list is empty (0) or fails in SQL (negative LIMIT)
        assertThatThrownBy(() -> bind(Map.of("trade-store.api.max-page-size", value)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("max-page-size must be at least 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"trade-store.kafka.partitions", "trade-store.kafka.replication-factor"})
    @Tag("A-42")
    void should_failAtStartup_when_aKafkaCountIsBelowOne(String property) {
        // Act + Assert: a clear configuration error instead of an obscure broker error at topic creation
        assertThatThrownBy(() -> bind(Map.of(property, "0")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("must be at least 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"trade-store.retry.max-attempts=0", "trade-store.retry.multiplier=0.5"})
    @Tag("US-303.3")
    void should_failAtStartup_when_aRetrySettingIsBelowOne(String setting) {
        // Arrange
        String[] pair = setting.split("=");

        // Act + Assert: zero attempts would drop messages unseen; a multiplier below 1 shrinks the backoff
        assertThatThrownBy(() -> bind(Map.of(pair[0], pair[1])))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause().hasMessageContaining("must be at least 1");
    }
}
