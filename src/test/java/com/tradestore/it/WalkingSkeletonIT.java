package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Phase 4 walking skeleton: the application starts against real PostgreSQL, MongoDB and Kafka
 * (Testcontainers), Flyway migrates, and operational endpoints respond.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WalkingSkeletonIT {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void should_reportHealthUp_when_databasesAreAvailable() {
        // Act
        ResponseEntity<Map> response = rest.getForEntity("/actuator/health", Map.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
        assertThat(response.getBody()).extractingByKey("components")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("db", "mongo");
    }

    @Test
    void should_applyFlywayBaseline_when_applicationStarts() {
        // Act
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);

        // Assert
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void should_reachKafkaCluster_when_applicationStarts() throws Exception {
        // Arrange
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            // Act
            String clusterId = admin.describeCluster().clusterId().get(30, TimeUnit.SECONDS);

            // Assert
            assertThat(clusterId).isNotBlank();
        }
    }

    @Test
    void should_exposePrometheusMetrics_when_applicationStarts() {
        // Act
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }
}
