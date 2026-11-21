package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.tradestore.domain.StoredTrade;
import com.tradestore.persistence.TradeRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** US-302: unreadable messages are moved aside to the dead-letter topic and audited, without blocking the stream. */
@IntegrationTest
class PoisonPillIT {

    private static final String TOPIC = "trades.inbound";
    private static final String DLT = "trades.inbound.DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final KafkaTemplate<String, String> kafka;
    private final TradeRepository trades;
    private final MongoTemplate mongo;
    private final ConsumerFactory<String, String> consumerFactory;

    private final String key = "P-" + UUID.randomUUID();
    private final String poison = "this is not JSON " + key;
    private Consumer<String, String> dltReader;

    PoisonPillIT(KafkaTemplate<String, String> kafka, TradeRepository trades, MongoTemplate mongo,
                 ConsumerFactory<String, String> consumerFactory) {
        this.kafka = kafka;
        this.trades = trades;
        this.mongo = mongo;
        this.consumerFactory = consumerFactory;
    }

    @BeforeEach
    void readTheDeadLetterTopic() {
        Properties fromTheStart = new Properties();
        fromTheStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dltReader = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null, null, fromTheStart);
        dltReader.subscribe(List.of(DLT));
    }

    @AfterEach
    void close() {
        dltReader.close();
    }

    /** Publishes the poison pill and then a valid trade with the same key (same partition, A-22). */
    private void publishPoisonThenValid() throws Exception {
        kafka.send(TOPIC, key, poison).get();
        kafka.send(TOPIC, key, """
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(key)).get();
    }

    private ConsumerRecord<String, String> deadLettered() {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(TIMEOUT).until(() -> {
            dltReader.poll(Duration.ofMillis(500)).forEach(r -> {
                if (poison.equals(r.value())) {
                    found.add(r);
                }
            });
            return !found.isEmpty();
        });
        return found.getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    @Tag("US-302.1")
    void should_deadLetterThePoisonPillAndStillStoreTheNextTrade_when_anUnreadableMessageArrives() throws Exception {
        // Act
        publishPoisonThenValid();

        // Assert: the valid trade behind it on the same partition is stored, and the pill is on the DLT
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trades.findById(key)).contains(new StoredTrade(key, 1, false)));
        assertThat(deadLettered().value()).isEqualTo(poison);
    }

    @Test
    @Tag("US-302.2")
    void should_auditAPoisonEntryWithTheReasonAndThePayload_when_aMessageIsDeadLettered() throws Exception {
        // Act
        publishPoisonThenValid();

        // Assert
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Document entry = mongo.getCollection("audit_events")
                    .find(new Document("outcome", "POISON").append("payload", poison)).first();
            assertThat(entry).isNotNull();
            assertThat(entry.getString("error")).contains("MalformedTradeException");
            assertThat(entry.getString("channel")).isEqualTo("STREAM");
        });
    }

    @Test
    @Tag("US-302.3")
    void should_carryTheOriginalTopicPartitionOffsetAndException_when_aMessageIsDeadLettered() throws Exception {
        // Act
        publishPoisonThenValid();
        ConsumerRecord<String, String> record = deadLettered();

        // Assert
        assertThat(header(record, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(record.headers().lastHeader("kafka_dlt-original-partition")).isNotNull();
        assertThat(record.headers().lastHeader("kafka_dlt-original-offset")).isNotNull();
        assertThat(header(record, "kafka_dlt-exception-fqcn")).isNotBlank();
    }
}
