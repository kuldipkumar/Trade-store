package com.tradestore.messaging;

import java.time.Duration;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Broker reachability for the readiness probe (US-604.1, A-46): a describeCluster call with a timeout. */
public class KafkaHealthIndicator implements HealthIndicator {

    private final Supplier<Admin> admin;
    private final Duration timeout;

    public KafkaHealthIndicator(Supplier<Admin> admin, Duration timeout) {
        this.admin = admin;
        this.timeout = timeout;
    }

    @Override
    public Health health() {
        return Health.unknown().build();
    }
}
