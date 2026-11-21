package com.tradestore.messaging;

import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * Publishes dead letters; a value over {@code kafka.max-message-bytes} is truncated (and flagged with a header) so
 * the dead-letter send itself cannot exceed the producer's limit and block the partition (US-607.1).
 */
public class TruncatingDeadLetterPublisher extends DeadLetterPublishingRecoverer {

    static final String TRUNCATED_HEADER = "trade-store-dlt-truncated";

    private final int maxMessageBytes;
    private final int keepChars;

    /** @param keepChars characters kept from an oversized value, the same cap as the audit payload */
    public TruncatingDeadLetterPublisher(KafkaOperations<?, ?> kafka,
                                         BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination,
                                         int maxMessageBytes, int keepChars) {
        super(kafka, destination);
        this.maxMessageBytes = maxMessageBytes;
        this.keepChars = keepChars;
    }

    @Override
    protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record, TopicPartition topicPartition,
                                                                  Headers headers, byte[] key, byte[] value) {
        ProducerRecord<Object, Object> deadLetter = super.createProducerRecord(record, topicPartition, headers, key, value);
        if (deadLetter.value() instanceof String text && tooLarge(text)) {
            ProducerRecord<Object, Object> truncated = new ProducerRecord<>(deadLetter.topic(), deadLetter.partition(),
                    deadLetter.timestamp(), deadLetter.key(), text.substring(0, Math.min(keepChars, text.length())),
                    deadLetter.headers());
            truncated.headers().add(TRUNCATED_HEADER, "true".getBytes(StandardCharsets.UTF_8));
            return truncated;
        }
        return deadLetter;
    }

    private boolean tooLarge(String text) {
        return text.length() > maxMessageBytes || text.getBytes(StandardCharsets.UTF_8).length > maxMessageBytes;
    }
}
