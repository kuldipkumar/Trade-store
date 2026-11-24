package com.tradestore.it;

import com.tradestore.config.ClockConfig;
import com.tradestore.config.TradeStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real infrastructure for integration tests (AGENTS.md section 5: no in-memory fakes).
 * Image versions match docker-compose.yml so tests and the local stack behave the same.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final String POSTGRES_IMAGE = "postgres:17.11-alpine";
    static final String MONGO_IMAGE = "mongo:8.0.38";
    static final String KAFKA_IMAGE = "apache/kafka:4.3.2";

    // Container output goes to the test log, so CI failures can be diagnosed.
    private static Slf4jLogConsumer logsOf(String name) {
        Logger logger = LoggerFactory.getLogger("container." + name);
        return new Slf4jLogConsumer(logger).withSeparateOutputStreams();
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE)).withLogConsumer(logsOf("postgres"));
    }

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        return new MongoDBContainer(DockerImageName.parse(MONGO_IMAGE)).withLogConsumer(logsOf("mongo"));
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE)).withLogConsumer(logsOf("kafka"));
    }

    /** Finished spans, kept in memory so tests can assert the traces (A-46, ADR-012). */
    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    /** Exported log records, kept in memory so tests can assert the OTLP log export (A-46, ADR-012). */
    @Bean
    InMemoryLogRecordExporter inMemoryLogRecordExporter() {
        return InMemoryLogRecordExporter.create();
    }

    /** Replaces the application's Clock in ITs so a test can pin "today" (see MutableClock). */
    @Bean
    @Primary
    MutableClock testClock(TradeStoreProperties properties) {
        return new MutableClock(ClockConfig.zoneOf(properties)); // same zone rule as production (ClockConfig)
    }
}
