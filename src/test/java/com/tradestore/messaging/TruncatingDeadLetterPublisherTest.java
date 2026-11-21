package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;

/** US-607.1: an oversized message can still be dead-lettered. */
class TruncatingDeadLetterPublisherTest {

    private final TruncatingDeadLetterPublisher publisher = new TruncatingDeadLetterPublisher(mock(KafkaOperations.class),
            KafkaConfig.deadLetterDestination("trades.inbound.DLT"), 100, 20);
    private final TopicPartition dlt = new TopicPartition("trades.inbound.DLT", -1);

    private ProducerRecord<Object, Object> deadLetter(String value) {
        return publisher.createProducerRecord(new ConsumerRecord<>("trades.inbound", 0, 1L, "T1", value), dlt,
                new RecordHeaders(), null, null);
    }

    @Test
    @Tag("US-607.1")
    void should_truncateTheValueAndFlagIt_when_theMessageIsOverTheLimit() {
        // Act: 150 bytes against a 100-byte limit (re-publishing it whole would exceed the producer's limit too)
        ProducerRecord<Object, Object> record = deadLetter("x".repeat(150));

        // Assert: the first 20 characters kept, like the audit payload; flagged so operators know
        assertThat(record.value()).isEqualTo("x".repeat(20));
        assertThat(record.headers().lastHeader(TruncatingDeadLetterPublisher.TRUNCATED_HEADER).value())
                .isEqualTo("true".getBytes(StandardCharsets.UTF_8));
        assertThat(record.key()).isEqualTo("T1");
    }

    @Test
    @Tag("US-302.1")
    void should_keepTheValueWhole_when_theMessageIsWithinTheLimit() {
        // Act
        ProducerRecord<Object, Object> record = deadLetter("not json");

        // Assert
        assertThat(record.value()).isEqualTo("not json");
        assertThat(record.headers().lastHeader(TruncatingDeadLetterPublisher.TRUNCATED_HEADER)).isNull();
    }
}
