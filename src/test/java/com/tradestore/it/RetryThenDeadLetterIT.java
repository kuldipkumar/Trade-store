package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Trade;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * US-303: a transient failure is retried with backoff; when it persists, the message is dead-lettered and audited
 * as FAILED. The SQL store is a spy that fails on demand. Short backoff so the test runs quickly (US-303.3).
 */
@IntegrationTest
@TestPropertySource(properties = {"trade-store.retry.initial-interval=100ms", "trade-store.retry.max-interval=200ms"})
class RetryThenDeadLetterIT {

    private static final String TOPIC = "trades.inbound";
    private static final String DLT = "trades.inbound.DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @MockitoSpyBean
    private TradeRepository trades;

    private final KafkaTemplate<String, String> kafka;
    private final MongoTemplate mongo;
    private final ConsumerFactory<String, String> consumerFactory;

    private final String flaky = "R-FLAKY-" + UUID.randomUUID();
    private final String broken = "R-BROKEN-" + UUID.randomUUID();
    private final AtomicInteger flakyFailures = new AtomicInteger();
    private Consumer<String, String> dltReader;

    RetryThenDeadLetterIT(KafkaTemplate<String, String> kafka, MongoTemplate mongo,
                          ConsumerFactory<String, String> consumerFactory) {
        this.kafka = kafka;
        this.mongo = mongo;
        this.consumerFactory = consumerFactory;
    }

    @BeforeEach
    void failOnDemand() {
        doAnswer(invocation -> {
            Trade trade = invocation.getArgument(0);
            if (trade.tradeId().equals(broken)) {
                throw new DataAccessResourceFailureException("PostgreSQL down");
            }
            if (trade.tradeId().equals(flaky) && flakyFailures.getAndIncrement() < 2) {
                throw new DataAccessResourceFailureException("PostgreSQL restarting");
            }
            return invocation.callRealMethod();
        }).when(trades).upsertIfNotOlder(any());

        Properties fromTheStart = new Properties();
        fromTheStart.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        dltReader = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null, null, fromTheStart);
        dltReader.subscribe(List.of(DLT));
    }

    @AfterEach
    void close() {
        dltReader.close();
    }

    private void publish(String tradeId) throws Exception {
        kafka.send(TOPIC, tradeId, """
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(tradeId)).get();
    }

    private List<ConsumerRecord<String, String>> deadLetters(String key, Duration within) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + within.toNanos();
        while (found.isEmpty() && System.nanoTime() < deadline) {
            dltReader.poll(Duration.ofMillis(500)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    @Tag("US-303.1")
    void should_storeTheTradeAfterRetriesWithoutDeadLettering_when_theStoreFailsTwiceThenRecovers() throws Exception {
        // Act
        publish(flaky);

        // Assert: stored on the third attempt; nothing on the dead-letter topic
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trades.findById(flaky)).contains(new StoredTrade(flaky, 1, false)));
        assertThat(flakyFailures).hasValueGreaterThanOrEqualTo(3);
        assertThat(deadLetters(flaky, Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @Tag("US-303.2")
    void should_deadLetterAndAuditAsFailedAfterThreeAttempts_when_theStoreKeepsFailing() throws Exception {
        // Act
        publish(broken);

        // Assert: on the DLT with the cause, audited as FAILED, after exactly 3 attempts (default max-attempts)
        List<ConsumerRecord<String, String>> dead = deadLetters(broken, TIMEOUT);
        assertThat(dead).hasSize(1);
        assertThat(header(dead.getFirst(), "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo(DataAccessResourceFailureException.class.getName());
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Document entry = mongo.getCollection("audit_events")
                    .find(new Document("outcome", "FAILED").append("payload", new Document("$regex", broken))).first();
            assertThat(entry).isNotNull();
            assertThat(entry.getString("error")).contains("DataAccessResourceFailureException");
        });
        verify(trades, times(3)).upsertIfNotOlder(argThat(t -> t.tradeId().equals(broken)));
        assertThat(trades.findById(broken)).isEmpty();
    }
}
