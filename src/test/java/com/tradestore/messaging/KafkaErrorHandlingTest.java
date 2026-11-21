package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tradestore.service.parsing.MalformedTradeException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/** How the listener's error handler classifies failures (US-302, US-303, ADR-002). */
class KafkaErrorHandlingTest {

    private final ConsumerRecordRecoverer recoverer = mock(ConsumerRecordRecoverer.class);
    private final DefaultErrorHandler handler = new KafkaConfig().errorHandler(recoverer);
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
    @Tag("US-303.1")
    void should_retryNotRecover_when_theDatabaseIsBrieflyUnavailable() {
        // Act
        boolean handled = fail(new DataAccessResourceFailureException("PostgreSQL restarting"));

        // Assert: redelivered after a backoff; nothing is dropped
        assertThat(handled).isFalse();
        verify(recoverer, never()).accept(any(), any());
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
}
