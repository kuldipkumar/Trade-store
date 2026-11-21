package com.tradestore.messaging;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Broker reachability for the readiness probe (US-604.1, A-46): a describeCluster call with a timeout. */
public class KafkaHealthIndicator implements HealthIndicator, DisposableBean {

    private final Supplier<Admin> admin;
    private final Duration timeout;

    public KafkaHealthIndicator(Supplier<Admin> admin, Duration timeout) {
        this.admin = admin;
        this.timeout = timeout;
    }

    @Override
    public Health health() {
        try {
            DescribeClusterResult cluster = admin.get()
                    .describeCluster(new DescribeClusterOptions().timeoutMs((int) timeout.toMillis()));
            String clusterId = cluster.clusterId().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            int nodes = cluster.nodes().get(timeout.toMillis(), TimeUnit.MILLISECONDS).size();
            return Health.up().withDetail("clusterId", clusterId).withDetail("nodes", nodes).build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Health.down(interrupted).build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }

    @Override
    public void destroy() {
    }
}
