package com.tradestore.messaging;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Broker reachability for the readiness probe (US-604.1, A-46): one describeCluster call under one deadline. The
 * admin client is created on first use and closed with the context. Details stay minimal because readiness is public.
 */
public class KafkaHealthIndicator implements HealthIndicator, DisposableBean {

    private final Supplier<Admin> factory;
    private final Duration timeout;
    private Admin admin;

    public KafkaHealthIndicator(Supplier<Admin> factory, Duration timeout) {
        this.factory = factory;
        this.timeout = timeout;
    }

    @Override
    public Health health() {
        try {
            DescribeClusterResult cluster = admin()
                    .describeCluster(new DescribeClusterOptions().timeoutMs((int) timeout.toMillis()));
            KafkaFuture<Collection<Node>> nodes = cluster.nodes();
            KafkaFuture.allOf(cluster.clusterId(), nodes).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Health.up().withDetail("nodes", nodes.get().size()).build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return down(interrupted);
        } catch (Exception unreachable) {
            return down(unreachable);
        }
    }

    private static Health down(Exception failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return Health.down().withDetail("error", cause.getClass().getName()).build();
    }

    private synchronized Admin admin() {
        if (admin == null) {
            admin = factory.get();
        }
        return admin;
    }

    @Override
    public synchronized void destroy() {
        if (admin != null) {
            admin.close(Duration.ofSeconds(5));
            admin = null;
        }
    }
}
