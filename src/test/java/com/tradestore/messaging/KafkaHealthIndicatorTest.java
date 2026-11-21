package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** US-604.1: the broker is part of readiness. */
class KafkaHealthIndicatorTest {

    private final Admin admin = mock(Admin.class);
    private final DescribeClusterResult cluster = mock(DescribeClusterResult.class);
    private final AtomicInteger created = new AtomicInteger();
    private final KafkaHealthIndicator indicator = new KafkaHealthIndicator(() -> {
        created.incrementAndGet();
        return admin;
    }, Duration.ofMillis(200));

    @Test
    @Tag("US-604.1")
    void should_reportUpWithTheClusterId_when_theBrokerAnswers() {
        // Arrange
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture("c-1"));
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(new Node(1, "kafka", 9092))));

        // Act
        Health health = indicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("nodes", 1).doesNotContainKey("clusterId");
    }

    @Test
    @Tag("US-604.1")
    void should_reportDownWithinTheTimeout_when_theBrokerNeverAnswers() {
        // Arrange: futures that never complete
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(new KafkaFutureImpl<>());
        when(cluster.nodes()).thenReturn(new KafkaFutureImpl<>());

        // Act
        long start = System.nanoTime();
        Health health = indicator.health();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Assert: DOWN after one 200 ms deadline, not one per future (code review of PR #66)
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(elapsedMs).isLessThan(380);
    }

    @Test
    @Tag("US-604.1")
    void should_reportOnlyTheErrorClass_when_theBrokerFails() {
        // Arrange
        KafkaFutureImpl<String> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new TimeoutException("broker kafka-internal.corp:9092 unreachable"));
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(failed);
        when(cluster.nodes()).thenReturn(failedNodes());

        // Act
        Health health = indicator.health();

        // Assert: readiness is public, so no host names or messages (code review of PR #66)
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).doesNotContain("kafka-internal.corp");
        assertThat(health.getDetails()).containsEntry("error", TimeoutException.class.getName());
    }

    private static KafkaFuture<java.util.Collection<Node>> failedNodes() {
        KafkaFutureImpl<java.util.Collection<Node>> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new TimeoutException("broker kafka-internal.corp:9092 unreachable"));
        return failed;
    }

    @Test
    @Tag("US-604.1")
    void should_closeTheAdminClient_when_theContextShutsDown() throws Exception {
        // Arrange: created on first use
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture("c-1"));
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of()));
        indicator.health();
        indicator.health();

        // Act
        indicator.destroy();

        // Assert: one client, closed (code review of PR #66)
        assertThat(created).hasValue(1);
        verify(admin).close(any(Duration.class));
    }
}
