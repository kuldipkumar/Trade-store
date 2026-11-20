package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Phase 4 walking skeleton: the application starts against real PostgreSQL, MongoDB and Kafka
 * (Testcontainers), Flyway migrates, and operational endpoints respond.
 */
@IntegrationTest
class WalkingSkeletonIT {

    @Autowired
    private RestTestClient rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    @Tag("US-604.1")
    void should_reportHealthUp_when_databasesAreAvailable() {
        // Act
        EntityExchangeResult<JsonNode> response = get("/actuator/health", JsonNode.class);

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        JsonNode health = response.getResponseBody();
        assertThat(health.path("status").asString()).isEqualTo("UP");
        assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");
        assertThat(health.path("components").path("mongo").path("status").asString()).isEqualTo("UP");
    }

    @Test
    @Tag("US-603.1")
    void should_applyFlywayBaseline_when_applicationStarts() {
        // Act
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class);

        // Assert
        assertThat(applied).isEqualTo(1);
    }

    @Test
    @Tag("US-603.1")
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
    @Tag("US-604.2")
    void should_exposePrometheusMetrics_when_applicationStarts() {
        // Act
        EntityExchangeResult<String> response = get("/actuator/prometheus", String.class);

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.getResponseBody()).contains("jvm_memory_used_bytes");
    }

    private <T> EntityExchangeResult<T> get(String path, Class<T> bodyType) {
        return rest.get().uri(path).exchange().expectBody(bodyType).returnResult();
    }
}
