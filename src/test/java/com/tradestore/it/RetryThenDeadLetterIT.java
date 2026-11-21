package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * US-303: a connection failure is retried with backoff until it recovers (D9); any other failure that persists is
 * dead-lettered and audited as FAILED. The SQL store is a spy that fails on demand. Short backoff so the test runs quickly (US-303.3).
 */
@IntegrationTest
// Startup expiry is off: it would call the spied repository on another thread while @BeforeEach stubs it, and
// Mockito stubbing is not thread-safe (the stub could land on markExpired instead).
@TestPropertySource(properties = {"trade-store.retry.initial-interval=100ms", "trade-store.retry.max-interval=200ms",
        "trade-store.expiry.run-at-startup=false"})
class RetryThenDeadLetterIT {

    private static final String TOPIC = "trades.inbound";
    private static final String DLT = "trades.inbound.DLT";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration GRACE = Duration.ofSeconds(2);

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
                throw new InvalidDataAccessResourceUsageException("relation \"trades\" does not exist");
            }
            if (trade.tradeId().equals(flaky) && flakyFailures.getAndIncrement() < 5) {
                // More failures than max-attempts (3): an outage is waited out, never dead-lettered (D9 option C)
                throw new DataAccessResourceFailureException("PostgreSQL restarting",
                        new SQLException("terminating connection due to administrator command", "57P01"));
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

    /** Dead letters for the key: waits up to {@code within} for the first, then 2 s more so a duplicate shows. */
    private List<ConsumerRecord<String, String>> deadLetters(String key, Duration within) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            dltReader.poll(Duration.ofMillis(500)).forEach(r -> {
                if (key.equals(r.key())) {
                    found.add(r);
                }
            });
            if (!found.isEmpty()) {
                deadline = Math.min(deadline, System.nanoTime() + GRACE.toNanos());
            }
        }
        return found;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    @Test
    @Tag("US-303.1")
    void should_storeTheTradeAfterRetriesWithoutDeadLettering_when_theDatabaseIsUnreachableForLongerThanTheRetryLimit()
            throws Exception {
        // Act
        publish(flaky);

        // Assert: stored on the sixth attempt; nothing on the dead-letter topic
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trades.findById(flaky)).contains(new StoredTrade(flaky, 1, false)));
        verify(trades, times(6)).upsertIfNotOlder(argThat(t -> t.tradeId().equals(flaky)));
        assertThat(deadLetters(flaky, Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @Tag("US-303.2")
    void should_deadLetterAndAuditAsFailedAfterThreeAttempts_when_aNonConnectionFailurePersists() throws Exception {
        // Act
        publish(broken);

        // Assert: not a connection problem (D9), so after exactly 3 attempts it is on the DLT and audited FAILED
        List<ConsumerRecord<String, String>> dead = deadLetters(broken, TIMEOUT);
        assertThat(dead).hasSize(1);
        assertThat(header(dead.getFirst(), "kafka_dlt-exception-cause-fqcn"))
                .isEqualTo(InvalidDataAccessResourceUsageException.class.getName());
        await().atMost(TIMEOUT).untilAsserted(() -> {
            Document entry = mongo.getCollection("audit_events")
                    .find(new Document("outcome", "FAILED").append("payload", new Document("$regex", broken))).first();
            assertThat(entry).isNotNull();
            assertThat(entry.getString("error")).contains("InvalidDataAccessResourceUsageException");
        });
        verify(trades, times(3)).upsertIfNotOlder(argThat(t -> t.tradeId().equals(broken)));
        assertThat(trades.findById(broken)).isEmpty();
    }
}
