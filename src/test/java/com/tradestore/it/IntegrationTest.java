package com.tradestore.it;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * One configuration for every integration test, so Spring caches a single application context and the
 * Testcontainers (Kafka, PostgreSQL, MongoDB) start once per test run.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// The daily expiry cron is off in ITs: tests run expiry explicitly, and a cron run at 00:05 could race them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "trade-store.expiry.cron=-")
@AutoConfigureMetrics // tests disable metrics exporters by default; we verify the real setup
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
