package com.tradestore.messaging;

import com.tradestore.service.parsing.MalformedTradeException;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka topics owned by the trade store (ADR-002). Declared here so they exist with the intended partition
 * count instead of being auto-created with broker defaults; consumers scale up to the partition count (A-42).
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    NewTopic tradesInbound(@Value("${trade-store.kafka.topic:trades.inbound}") String topic,
                           @Value("${trade-store.kafka.partitions:6}") int partitions,
                           @Value("${trade-store.kafka.replication-factor:1}") short replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    /**
     * The listener's error handler (ADR-002). A malformed message is never retried (A-27). Anything else, for
     * example the database briefly unavailable, is redelivered with exponential backoff (1 s, doubling to 10 s)
     * until it succeeds, so no trade is dropped; the partition waits meanwhile. US-302/US-303 add the
     * dead-letter topic and a retry limit.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        return errorHandler((record, e) -> log.error("Unreadable message on {}-{} at offset {} is skipped: {}",
                record.topic(), record.partition(), record.offset(), e.toString()));
    }

    DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(10_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedTradeException.class);
        return handler;
    }
}
