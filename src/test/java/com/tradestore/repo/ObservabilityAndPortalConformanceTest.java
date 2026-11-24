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
    void should_offerPrometheusAndAProvisionedGrafanaDashboard_when_theStackStarts() {
        // Act
        String compose = read("docker-compose.yml");
        String dashboard = read("observability/grafana/dashboards/trade-store.json");

        // Assert: part of the default stack (the app always exports), scraping the app, with the business panels
        assertThat(compose).doesNotContain("profiles: [observability]").contains("prom/prometheus:").contains("grafana/grafana:");
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

    @Test
    @Tag("A-47")
    void should_deployOnlyCodeFromThisRepositorysMain_when_aWorkflowRunTriggersThePortal() {
        // Arrange
        String portal = read(".github/workflows/portal.yml");
        String build = portal.substring(portal.indexOf("  build:"), portal.indexOf("  deploy:"));
        String deploy = portal.substring(portal.indexOf("  deploy:"));

        // Act + Assert: a fork's PR named main must not reach the deploy (code review of PR #69)
        assertThat(build).contains("github.event.workflow_run.event == 'push'")
                .contains("github.event.workflow_run.head_repository.full_name == github.repository");
        assertThat(build).doesNotContain("pages: write").doesNotContain("id-token: write");
        assertThat(deploy).contains("pages: write").contains("id-token: write");
        assertThat(portal.substring(0, portal.indexOf("jobs:"))).doesNotContain("pages: write");
    }

    @Test
    @Tag("US-609.1")
    void should_publishSwaggerDocumentationForTheRestApi_when_thePortalIsBuilt() {
        // Arrange
        assertThat(exists("portal/api.html")).isTrue();

        // Act + Assert: Swagger UI over the OpenAPI contract, linked from the portal, published with it
        assertThat(read("portal/api.html")).contains("swagger-ui-bundle.js").contains("openapi.yaml");
        assertThat(read("portal/index.html")).contains("api.html");
        assertThat(read(".github/workflows/portal.yml")).contains("docs/api/openapi.yaml").contains("portal/api.html");
    }

    @Test
    @Tag("US-604.2")
    void should_snapshotTheGrafanaPanelsIntoThePortal_when_ciRunsTheObservabilityStack() {
        // Act
        String ci = read(".github/workflows/ci.yml");

        // Assert: the shared load file over both channels, then the dashboard's own queries (before the ZAP scan)
        assertThat(ci).contains("scripts/load_trades.py").contains("loadtest/trades-10k.csv")
                .contains("--channel rest").contains("/reports/audit?outcome=REJECTED").contains("/trades?size=")
                .contains("scripts/observability_snapshot.py")
                .contains("observability-snapshot.json");
        assertThat(ci.indexOf("observability_snapshot.py")).isLessThan(ci.indexOf("zap-api-scan.py"));
        assertThat(read(".github/workflows/portal.yml")).contains("observability-snapshot.json");
        assertThat(read("portal/index.html")).contains("data/observability.json");
    }

    @Test
    @Tag("A-46")
    void should_sendTracesToTempoAndLogsToLokiAndLinkThemInGrafana_when_theStackStarts() {
        // Act
        String compose = read("docker-compose.yml");
        String datasources = read("observability/grafana/provisioning/datasources/datasource.yml");
        String dashboard = read("observability/grafana/dashboards/trade-store.json");

        // Assert: the app exports over OTLP to Tempo and Loki (ADR-012)
        assertThat(compose).contains("grafana/tempo:").contains("grafana/loki:")
                .contains("MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT: http://tempo:4318/v1/traces")
                .contains("MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT: http://loki:3100/otlp/v1/logs")
                .contains("--enable-feature=exemplar-storage");
        // ... and Grafana links metrics -> traces (exemplars), logs -> traces (trace_id) and traces -> logs
        assertThat(datasources).contains("type: tempo").contains("type: loki").contains("exemplarTraceIdDestinations")
                .contains("derivedFields").contains("tracesToLogsV2");
        assertThat(dashboard).contains("\"type\": \"logs\"").contains("\"type\": \"traces\"");
        assertThat(read("scripts/observability-smoke.sh")).contains("localhost:3200/api/search").contains("localhost:3100/loki/api/v1/query_range");
    }
}
