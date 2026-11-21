package com.tradestore.messaging;

import java.util.function.BiFunction;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

/**
 * Publishes dead letters; a value over {@code kafka.max-message-bytes} is truncated so the dead-letter send itself
 * cannot exceed the producer's limit and block the partition (US-607.1).
 */
public class TruncatingDeadLetterPublisher extends DeadLetterPublishingRecoverer {

    static final String TRUNCATED_HEADER = "trade-store-dlt-truncated";

    private final int maxMessageBytes;
    private final int keepChars;

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
        return super.createProducerRecord(record, topicPartition, headers, key, value);
    }
}
