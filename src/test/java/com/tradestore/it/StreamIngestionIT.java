package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import com.tradestore.domain.StoredTrade;
import com.tradestore.persistence.TradeRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * US-301: trades published to the stream are stored and audited with the same rules as REST (A-21), against
 * the real Kafka, PostgreSQL and MongoDB containers. The timeout is a test timeout, not an SLA (CR-4).
 */
@IntegrationTest
class StreamIngestionIT {

    private static final String TOPIC = "trades.inbound";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final KafkaTemplate<String, String> kafka;
    private final TradeRepository trades;
    private final MongoTemplate mongo;
    private final KafkaListenerEndpointRegistry registry;
    private final ConsumerFactory<?, ?> consumerFactory;
    private final KafkaAdmin kafkaAdmin;

    private final String tradeId = "K-" + UUID.randomUUID();

    private final RestTestClient rest;

    StreamIngestionIT(KafkaTemplate<String, String> kafka, TradeRepository trades, MongoTemplate mongo,
                      KafkaListenerEndpointRegistry registry, ConsumerFactory<?, ?> consumerFactory, KafkaAdmin kafkaAdmin,
                      RestTestClient rest) {
        this.rest = rest;
        this.kafkaAdmin = kafkaAdmin;
        this.registry = registry;
        this.consumerFactory = consumerFactory;
        this.kafka = kafka;
        this.trades = trades;
        this.mongo = mongo;
    }

    private void publish(int version) throws Exception {
        String json = """
                {"tradeId":"%s","version":%d,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(tradeId, version);
        kafka.send(TOPIC, tradeId, json).get();
    }

    private long audit(String outcome) {
        return mongo.getCollection("audit_events").countDocuments(new Document("tradeId", tradeId).append("outcome", outcome));
    }

    @Test
    @Tag("US-301.1")
    @Tag("US-501.1")
    @Tag("US-603.2")
    void should_storeAndAuditATrade_when_itIsPublishedToTheStream() throws Exception {
        // Act
        publish(1);

        // Assert: stored, and audited with channel STREAM
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(trades.findById(tradeId)).contains(new StoredTrade(tradeId, 1, false));
            Document entry = mongo.getCollection("audit_events").find(new Document("tradeId", tradeId)).first();
            assertThat(entry).isNotNull();
            assertThat(entry.getString("outcome")).isEqualTo("ACCEPTED");
            assertThat(entry.getString("channel")).isEqualTo("STREAM");
        });
        // ... and queryable over REST (US-603.2: stream -> stored -> queryable)
        JsonNode body = rest.get().uri("/trades/" + tradeId).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body.path("version").asInt()).isEqualTo(1);
    }

    @Test
    @Tag("US-301.2")
    void should_processVersionsInOrder_when_theyArePublishedWithTheTradeIdAsKey() throws Exception {
        // Act: v1, v2, v3 keyed by tradeId, so they share a partition (A-22)
        publish(1);
        publish(2);
        publish(3);

        // Assert: v3 stored, and no false LOWER_VERSION rejections
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(trades.findById(tradeId).map(StoredTrade::version)).contains(3);
            assertThat(audit("ACCEPTED") + audit("REPLACED")).isEqualTo(3);
        });
        assertThat(audit("REJECTED")).isZero();
    }

    @Test
    @Tag("US-301.3")
    void should_commitOffsetsOnlyAfterProcessing_when_theListenerContainerRuns() {
        // Act
        var container = registry.getAllListenerContainers().iterator().next();
        var consumerConfig = consumerFactory.getConfigurationProperties();

        // Assert: no background auto-commit; the container commits after the listener returns, so a message
        // being processed when the service stops is delivered again after restart (A-43)
        assertThat(consumerConfig.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isIn(false, "false");
        assertThat(container.getContainerProperties().getAckMode())
                .isIn(ContainerProperties.AckMode.BATCH, ContainerProperties.AckMode.RECORD);
    }

    @Test
    @Tag("A-42")
    void should_declareTheInboundTopicWithSixPartitions_when_theApplicationStarts() throws Exception {
        // Act
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            var description = admin.describeTopics(java.util.List.of(TOPIC)).allTopicNames().get().get(TOPIC);

            // Assert: consumers scale up to the partition count (A-42)
            assertThat(description.partitions()).hasSize(6);
        }
    }

    @Test
    @Tag("US-304.1")
    void should_auditARejectionOnceAndNotRetryIt_when_aLowerVersionIsConsumed() throws Exception {
        // Arrange
        publish(2);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trades.findById(tradeId).map(StoredTrade::version)).contains(2));

        // Act
        publish(1);

        // Assert: exactly one REJECTED entry, and it stays one (no redelivery); v2 is still stored
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(audit("REJECTED")).isEqualTo(1));
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(audit("REJECTED")).isEqualTo(1));
        assertThat(trades.findById(tradeId).map(StoredTrade::version)).contains(2);
    }
}
