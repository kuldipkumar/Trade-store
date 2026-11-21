package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.tradestore.domain.StoredTrade;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.service.parsing.MalformedTradeException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.client.RestTestClient;
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

    private final RestTestClient rest;

    PoisonPillIT(KafkaTemplate<String, String> kafka, TradeRepository trades, MongoTemplate mongo,
                 ConsumerFactory<String, String> consumerFactory, RestTestClient rest) {
        this.rest = rest;
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
    private RecordMetadata publishPoisonThenValid() throws Exception {
        RecordMetadata sent = kafka.send(TOPIC, key, poison).get().getRecordMetadata();
        kafka.send(TOPIC, key, """
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(key)).get();
        return sent;
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
    @Tag("US-603.2")
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
        RecordMetadata sent = publishPoisonThenValid();
        ConsumerRecord<String, String> record = deadLettered();

        // Assert: the values point back at the exact message, and name the real cause
        assertThat(header(record, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
        assertThat(ByteBuffer.wrap(record.headers().lastHeader("kafka_dlt-original-partition").value()).getInt())
                .isEqualTo(sent.partition());
        assertThat(ByteBuffer.wrap(record.headers().lastHeader("kafka_dlt-original-offset").value()).getLong())
                .isEqualTo(sent.offset());
        assertThat(header(record, "kafka_dlt-exception-cause-fqcn")).isEqualTo(MalformedTradeException.class.getName());
        assertThat(record.key()).isEqualTo(key);
    }

    @Test
    @Tag("US-604.2")
    void should_exposeTheDeadLetterCounter_when_aMessageHasBeenDeadLettered() throws Exception {
        // Arrange
        publishPoisonThenValid();
        deadLettered();

        // Act
        String metrics = rest.get().uri("/actuator/prometheus").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        // Assert: alongside the outcome counters and the latency timer (StoreTradeIT)
        assertThat(metrics).containsPattern("trade_deadletter_total\\{[^}]*type=\"POISON\"");
    }
}
