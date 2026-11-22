package com.tradestore.repo;

import static com.tradestore.repo.RepositoryFiles.exists;
import static com.tradestore.repo.RepositoryFiles.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-604.2 dashboard and US-609 portal: a local Grafana dashboard and the published project portal. */
class ObservabilityAndPortalConformanceTest {

    @Test
    @Tag("US-604.2")
    void should_offerPrometheusAndAProvisionedGrafanaDashboard_when_theObservabilityProfileStarts() {
        // Act
        String compose = read("docker-compose.yml");
        String dashboard = read("observability/grafana/dashboards/trade-store.json");

        // Assert: opt-in profile, scraping the app, with the business panels (LLD section 8)
        assertThat(compose).contains("profiles: [observability]").contains("prom/prometheus:").contains("grafana/grafana:");
        assertThat(read("observability/prometheus.yml")).contains("/actuator/prometheus").contains("app:8080");
        for (String metric : List.of("trade_outcomes_total", "trade_processing_seconds_bucket", "trade_deadletter_total",
                "trade_retries_total", "trade_outbox_backlog_size", "http_server_requests_seconds_bucket")) {
            assertThat(dashboard).as(metric).contains(metric);
        }
    }

    @Test
    @Tag("US-609.1")
    void should_publishThePortalWithTheLatestReports_when_ciSucceedsOnMain() {
        // Arrange
        assertThat(exists(".github/workflows/portal.yml")).isTrue();
        String portal = read(".github/workflows/portal.yml");

        // Act + Assert: after a green CI run on main, the page and the run's reports go to GitHub Pages
        assertThat(portal).contains("workflow_run:").contains("workflows: [CI]").contains("conclusion == 'success'")
                .contains("actions/download-artifact@").contains("scripts/portal_data.py")
                .contains("actions/upload-pages-artifact@").contains("actions/deploy-pages@");
        assertThat(read("portal/index.html")).contains("data/portal.json").contains("api.github.com");
        assertThat(read("README.md")).contains("kuldipkumar.github.io/Trade-store");
    }
}
