package com.tradestore.messaging;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.service.AuditEventFactory;
import com.tradestore.service.parsing.MalformedTradeException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.BiFunction;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.invocation.MethodArgumentResolutionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.function.SingletonSupplier;

/**
 * Kafka topics owned by the trade store (ADR-002). Declared here so they exist with the intended partition
 * count instead of being auto-created with broker defaults; consumers scale up to the partition count (A-42).
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    NewTopic tradesInbound(TradeStoreProperties properties) {
        TradeStoreProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.topic()).partitions(kafka.partitions()).replicas(kafka.replicationFactor()).build();
    }

    /** Broker reachability, in the readiness group (US-604.1). One admin client, created on first use. */
    @Bean
    KafkaHealthIndicator kafkaHealthIndicator(KafkaAdmin kafkaAdmin) {
        Supplier<Admin> admin = SingletonSupplier.of(() -> Admin.create(kafkaAdmin.getConfigurationProperties()));
        return new KafkaHealthIndicator(admin, Duration.ofSeconds(3));
    }

    /** The dead-letter topic (US-302), sized like the inbound topic; records are partitioned by key. */
    @Bean
    NewTopic tradesDeadLetter(TradeStoreProperties properties) {
        TradeStoreProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.dltTopic()).partitions(kafka.partitions()).replicas(kafka.replicationFactor()).build();
    }

    /**
     * The listener's error handler (ADR-002). A malformed message is never retried (A-27): it is published to the
     * dead-letter topic with Spring's diagnostic headers and audited as POISON (US-302). A connection failure (the
     * database unreachable) is retried with exponential backoff until it succeeds, so an outage never loses a trade
     * or reorders a key's versions (D9 option C); the partition waits meanwhile. Any other failure is retried up to
     * {@code trade-store.retry.max-attempts} deliveries (default 3), then dead-lettered and audited as FAILED (US-303).
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafka, TradeStoreProperties properties,
                                          AuditEventFactory events, AuditEventStore audit, MeterRegistry registry) {
        String deadLetterTopic = properties.kafka().dltTopic();
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka, deadLetterDestination(deadLetterTopic));
        return errorHandler(new DeadLetterAuditRecoverer(publisher, events, audit, registry, KafkaConfig::isUnreadable),
                properties.retry(), registry);
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

    private static Throwable rootCause(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private static ExponentialBackOff backOff(TradeStoreProperties.Retry retry) {
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    /** SQLStates meaning the connection is gone: class 08, and 57P01-57P03 (server shutting down or restarting). */
    private static final List<String> CONNECTION_SQL_STATES = List.of("57P01", "57P02", "57P03");

    /**
     * The database is unreachable (D9 option C), anywhere in the cause chain: no connection could be obtained, or
     * the connection was lost. Errors that repeat with a healthy database (timeouts, deadlocks, limits) are not.
     */
    static boolean isConnectionFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof CannotCreateTransactionException || t instanceof CannotGetJdbcConnectionException
                    || t instanceof SQLTransientConnectionException || t instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || CONNECTION_SQL_STATES.contains(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

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

    DefaultErrorHandler errorHandler(ConsumerRecordRecoverer recoverer, TradeStoreProperties.Retry retry,
                                     MeterRegistry registry) {
        ExponentialBackOff limited = backOff(retry);
        limited.setMaxAttempts(retry.maxAttempts() - 1); // retries after the first delivery
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, limited);
        NOT_RETRYABLE.forEach(handler::addNotRetryableExceptions);
        // An outage wins over a not-retryable type deeper in its cause chain (classification looks at the top first).
        handler.addRetryableExceptions(CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class);
        // D9 option C: the database being unreachable is waited out, never dead-lettered; the partition pauses.
        ExponentialBackOff unlimited = backOff(retry);
        handler.setBackOffFunction((record, failure) -> isConnectionFailure(failure) ? unlimited : null);
        handler.setRetryListeners((record, failure, attempt) -> {
            // Called for every failed delivery; count only those that will be redelivered (AGENTS.md section 4).
            if (isConnectionFailure(failure) || (!isUnreadable(failure) && attempt < retry.maxAttempts())) {
                registry.counter("trade.retries").increment();
                log.warn("Retrying position={}-{}-{} attempt={}{}: {}", record.topic(), record.partition(),
                        record.offset(), attempt, isConnectionFailure(failure) ? " (until the database is back)"
                                : " of " + retry.maxAttempts(), rootCause(failure).getClass().getName());
            }
        });
        return handler;
    }
}
