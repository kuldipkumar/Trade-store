package com.tradestore.messaging;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import com.tradestore.service.parsing.MalformedTradeException;
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

    /**
     * The dead-letter topic (US-302). It has the same partition count as the inbound topic because a dead letter
     * keeps its original partition number (docs/05-lld.md section 6.2).
     */
    @Bean
    NewTopic tradesDeadLetter(TradeStoreProperties properties) {
        TradeStoreProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.dltTopic()).partitions(kafka.partitions()).replicas(kafka.replicationFactor()).build();
    }

    /**
     * The listener's error handler (ADR-002). A malformed message is never retried (A-27): it is published to the
     * dead-letter topic with Spring's diagnostic headers and audited as POISON (US-302). Anything else, for
     * example the database briefly unavailable, is redelivered with exponential backoff (1 s, doubling to 10 s)
     * until it succeeds, so no trade is dropped; the partition waits meanwhile. US-303 adds a retry limit.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafka, TradeStoreProperties properties,
                                          AuditEventFactory events, AuditEventStore audit, MeterRegistry registry) {
        String deadLetterTopic = properties.kafka().dltTopic();
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka, deadLetterDestination(deadLetterTopic));
        return errorHandler(new DeadLetterAuditRecoverer(publisher, events, audit, registry));
    }

    static BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterDestination(String topic) {
        return (record, e) -> new TopicPartition(topic, record.partition());
    }

    DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(10_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedTradeException.class);
        return handler;
    }
}
