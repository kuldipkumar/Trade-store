package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

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
    private final KafkaHealthIndicator indicator = new KafkaHealthIndicator(() -> admin, Duration.ofMillis(200));

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
        assertThat(health.getDetails()).containsEntry("clusterId", "c-1").containsEntry("nodes", 1);
    }

    @Test
    @Tag("US-604.1")
    void should_reportDown_when_theBrokerDoesNotAnswerInTime() {
        // Arrange
        KafkaFutureImpl<String> unanswered = new KafkaFutureImpl<>();
        unanswered.completeExceptionally(new TimeoutException("no broker"));
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(unanswered);

        // Act
        Health health = indicator.health();

        // Assert: readiness goes DOWN, so traffic is not routed to an instance that can't consume
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }
}
