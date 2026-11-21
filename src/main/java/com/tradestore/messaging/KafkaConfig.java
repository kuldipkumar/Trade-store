package com.tradestore.messaging;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import com.tradestore.service.parsing.MalformedTradeException;
import java.util.List;
import java.util.function.BiFunction;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka topics owned by the trade store (ADR-002). Declared here so they exist with the intended partition
 * count instead of being auto-created with broker defaults; consumers scale up to the partition count (A-42).
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    @Bean
    NewTopic tradesInbound(TradeStoreProperties properties) {
        TradeStoreProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.topic()).partitions(kafka.partitions()).replicas(kafka.replicationFactor()).build();
    }

    /** The dead-letter topic (US-302), sized like the inbound topic; records are partitioned by key. */
    @Bean
    NewTopic tradesDeadLetter(TradeStoreProperties properties) {
        TradeStoreProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.dltTopic()).partitions(kafka.partitions()).replicas(kafka.replicationFactor()).build();
    }

    /**
     * The listener's error handler (ADR-002). A malformed message is never retried (A-27): it is published to the
     * dead-letter topic with Spring's diagnostic headers and audited as POISON (US-302). Anything else, for
     * example the database briefly unavailable, is redelivered with exponential backoff (default 1 s, doubling
     * to 10 s) up to {@code trade-store.retry.max-attempts} deliveries (default 3); the partition waits meanwhile.
     * Then it is dead-lettered and audited as FAILED (US-303).
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafka, TradeStoreProperties properties,
                                          AuditEventFactory events, AuditEventStore audit, MeterRegistry registry) {
        String deadLetterTopic = properties.kafka().dltTopic();
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka, deadLetterDestination(deadLetterTopic));
        return errorHandler(new DeadLetterAuditRecoverer(publisher, events, audit, registry, KafkaConfig::isUnreadable),
                properties.retry());
    }

    /**
     * Where a dead letter goes: the producer chooses the partition by key (-1), so it exists even when the inbound
     * topic has more partitions than the dead-letter topic. The original partition is in the headers (US-302.3).
     */
    static BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterDestination(String topic) {
        return (record, e) -> new TopicPartition(topic, -1);
    }

    /** Ours, added to the handler's not-retryable list (LLD section 6.2). */
    private static final List<Class<? extends Exception>> NOT_RETRYABLE =
            List.of(MalformedTradeException.class, IllegalArgumentException.class);

    /** Ours plus DefaultErrorHandler's built-in not-retryable exceptions: all are recovered at once. */
    private static final List<Class<? extends Throwable>> UNREADABLE = List.of(MalformedTradeException.class,
            IllegalArgumentException.class, DeserializationException.class, MessageConversionException.class,
            ConversionException.class, MethodArgumentResolutionException.class, NoSuchMethodException.class,
            ClassCastException.class);

    /** A failure that retrying can't fix, anywhere in the cause chain: the message itself is unusable (POISON). */
    static boolean isUnreadable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            Throwable current = t;
            if (UNREADABLE.stream().anyMatch(type -> type.isInstance(current))) {
                return true;
            }
        }
        return false;
    }

    DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, TradeStoreProperties.Retry retry) {
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts() - 1); // retries after the first delivery
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        NOT_RETRYABLE.forEach(handler::addNotRetryableExceptions);
        return handler;
    }
}
