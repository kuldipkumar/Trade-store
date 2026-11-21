package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.stream.Stream;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.service.parsing.MalformedTradeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import java.sql.SQLException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/** How the listener's error handler classifies failures (US-302, US-303, ADR-002). */
class KafkaErrorHandlingTest {

    private final ConsumerRecordRecoverer recoverer = mock(ConsumerRecordRecoverer.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DefaultErrorHandler handler = new KafkaConfig().errorHandler(recoverer,
            new TradeStoreProperties.Retry(3, Duration.ofMillis(1), 2.0, Duration.ofMillis(2)), registry);
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("trades.inbound", 0, 42L, "T1", "{bad");

    private boolean fail(Exception cause) {
        return handler.handleOne(new ListenerExecutionFailedException("listener failed", cause), record,
                mock(Consumer.class), mock(MessageListenerContainer.class));
    }

    @Test
    @Tag("US-302.1")
    void should_recoverAMalformedMessageAtOnceWithoutRetries_when_itCannotBeParsed() {
        // Act
        boolean handled = fail(new MalformedTradeException("The body is not valid JSON"));

        // Assert: handed to the recoverer on the first failure (A-27: never retried)
        assertThat(handled).isTrue();
        verify(recoverer).accept(any(), any());
    }

    @Test
    @Tag("US-302.1")
    void should_recoverAtOnceWithoutRetries_when_anIllegalArgumentIsThrown() {
        // Act: the same input would fail the same way on every retry (LLD section 6.2, ADR-002)
        boolean handled = fail(new IllegalArgumentException("unusable value"));

        // Assert
        assertThat(handled).isTrue();
        verify(recoverer).accept(any(), any());
    }

    @Test
    @Tag("US-303.1")
    void should_retryNotRecover_when_theDatabaseIsBrieflyUnavailable() {
        // Act
        boolean handled = fail(new DataAccessResourceFailureException("PostgreSQL restarting"));

        // Assert: redelivered after a backoff; nothing is dropped
        assertThat(handled).isFalse();
        verify(recoverer, never()).accept(any(), any());
    }

    @Test
    @Tag("US-303.2")
    @Tag("US-303.3")
    void should_recoverOnTheConfiguredAttempt_when_aFailureOtherThanConnectivityPersists() {
        // Arrange: not a connection problem, so waiting longer would not help (D9)
        InvalidDataAccessResourceUsageException down = new InvalidDataAccessResourceUsageException("relation missing");

        // Act: max-attempts 3 = the first delivery and two retries
        boolean first = fail(down);
        boolean second = fail(down);
        boolean third = fail(down);

        // Assert
        assertThat(first).isFalse();
        assertThat(second).isFalse();
        assertThat(third).isTrue();
        verify(recoverer).accept(any(), any());
    }

    @Test
    @Tag("US-302.1")
    void should_letKafkaChooseTheDeadLetterPartition_when_aMessageIsDeadLettered() {
        // Arrange: inbound partition 9 may not exist on the dead-letter topic (code review of PR #61)
        ConsumerRecord<String, String> onPartition9 = new ConsumerRecord<>("trades.inbound", 9, 7L, "T1", "{bad");

        // Act
        TopicPartition destination = KafkaConfig.deadLetterDestination("trades.inbound.DLT")
                .apply(onPartition9, new RuntimeException());

        // Assert: a negative partition makes the producer choose one by key, so it always exists
        assertThat(destination).isEqualTo(new TopicPartition("trades.inbound.DLT", -1));
    }

    static Stream<Exception> notRetryable() {
        return Stream.of(new MalformedTradeException("not JSON"), new IllegalArgumentException("bad value"),
                new DeserializationException("too large", new byte[0], false, new IllegalStateException()),
                new MessageConversionException("cannot convert"),
                new RuntimeException("wrapped", new MalformedTradeException("not JSON")));
    }

    @ParameterizedTest
    @MethodSource("notRetryable")
    @Tag("US-302.2")
    void should_classifyAsUnreadable_when_theFailureIsNeverRetried(Exception failure) {
        // Act + Assert: the same set the handler recovers at once, so the audit says POISON, not FAILED
        assertThat(KafkaConfig.isUnreadable(failure)).isTrue();
    }

    @Test
    @Tag("US-303.2")
    void should_notClassifyAsUnreadable_when_theFailureIsTransient() {
        // Act + Assert
        assertThat(KafkaConfig.isUnreadable(new DataAccessResourceFailureException("PostgreSQL down"))).isFalse();
    }

    @Test
    @Tag("US-303.1")
    @Tag("US-604.2")
    @ExtendWith(OutputCaptureExtension.class)
    void should_countAndLogEachRetry_when_aTransientFailureIsRedelivered(CapturedOutput output) {
        // Act
        fail(new DataAccessResourceFailureException("PostgreSQL restarting"));

        // Assert: AGENTS.md section 4, every retry is visible (code review of PR #62)
        assertThat(registry.counter("trade.retries").count()).isEqualTo(1.0);
        assertThat(output).contains("WARN").contains("position=trades.inbound-0-42 attempt=1")
                .contains("DataAccessResourceFailureException").doesNotContain("{bad");
    }

    @Test
    @Tag("US-302.1")
    void should_notCountARetry_when_theFailureIsNotRetryable() {
        // Act
        fail(new MalformedTradeException("not JSON"));

        // Assert
        assertThat(registry.find("trade.retries").counter()).isNull();
    }

    static Stream<Exception> connectionFailures() {
        DataAccessResourceFailureException lost = new DataAccessResourceFailureException("I/O error",
                new SQLException("connection lost", "08006"));
        return Stream.of(lost, new CannotCreateTransactionException("no connection"),
                new CannotGetJdbcConnectionException("pool exhausted"),
                new DataAccessResourceFailureException("restarting", new SQLException("admin shutdown", "57P01")),
                new RuntimeException("wrapped", lost),
                // Precedence: an outage wins over a not-retryable type deeper in the chain (code review of PR #65)
                new CannotCreateTransactionException("no connection", new IllegalArgumentException("bad url")));
    }

    static Stream<Exception> boundedFailures() {
        return Stream.of(new QueryTimeoutException("statement timeout", new SQLException("canceled", "57014")),
                new DataAccessResourceFailureException("program limit", new SQLException("too complex", "54000")),
                new InvalidDataAccessResourceUsageException("relation missing"));
    }

    @ParameterizedTest
    @MethodSource("boundedFailures")
    @Tag("US-303.2")
    void should_deadLetterOnTheThirdAttempt_when_theDatabaseIsUpButTheSameErrorRepeats(Exception failure) {
        // Act + Assert: these fail the same way every time with a healthy database, so they must not block the
        // partition forever (code review of PR #65)
        assertThat(fail(failure)).isFalse();
        assertThat(fail(failure)).isFalse();
        assertThat(fail(failure)).isTrue();
        verify(recoverer).accept(any(), any());
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    @Tag("US-303.1")
    @Tag("US-303.2")
    void should_keepRetryingAndNeverDeadLetter_when_theDatabaseIsUnreachable(Exception failure) {
        // Act: well past max-attempts (3)
        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(fail(failure)).isFalse();
        }

        // Assert: D9 option C, no trade is dead-lettered because of an outage; every retry is visible
        verifyNoInteractions(recoverer);
        assertThat(registry.counter("trade.retries").count()).isEqualTo(10.0);
    }
}
