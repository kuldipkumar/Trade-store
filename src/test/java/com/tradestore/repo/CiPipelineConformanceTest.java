package com.tradestore.repo;

import static com.tradestore.repo.RepositoryFiles.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * US-601, US-602, US-606.1: the pipeline in .github/workflows/ci.yml is an executable spec; these checks keep it
 * honest. The gates themselves are proven on every CI run (a failing step fails the build).
 */
class CiPipelineConformanceTest {

    private final String ci = read(".github/workflows/ci.yml");
    private final String pom = read("pom.xml");

    private int position(String step) {
        int at = ci.indexOf("- name: " + step);
        assertThat(at).as("step '%s' in ci.yml", step).isNotNegative();
        return at;
    }

    @Test
    @Tag("US-601.1")
    void should_runTheAgentsMdStagesInOrder_when_aPullRequestOrPushToMainRuns() {
        // Arrange: AGENTS.md section 6
        List<String> stages = List.of("Checkout", "Set up JDK 21", "Render PlantUML diagrams", "Unit tests",
                "Integration tests and coverage gate", "Trivy dependency scan", "Build Docker image", "Upload reports");

        // Act
        List<Integer> positions = stages.stream().map(this::position).toList();

        // Assert
        assertThat(positions).isSorted();
        assertThat(ci).contains("push:\n    branches: [main]").contains("pull_request:\n    branches: [main]");
    }

    @Test
    @Tag("US-601.2")
    void should_failTheBuild_when_aTestFails() {
        // Act + Assert: no step or plugin swallows failures
        assertThat(ci).doesNotContain("continue-on-error: true").doesNotContain("-Dmaven.test.failure.ignore");
        assertThat(pom).doesNotContain("testFailureIgnore").doesNotContain("<testFailureIgnore>true");
    }

    @Test
    @Tag("US-601.3")
    void should_failTheBuild_when_lineCoverageIsBelow80Percent() {
        // Act + Assert: the JaCoCo check goal on verify, LINE COVEREDRATIO with the 0.80 minimum (A-48)
        assertThat(pom).contains("<coverage.line.minimum>0.80</coverage.line.minimum>")
                .contains("<goal>check</goal>").contains("<counter>LINE</counter>").contains("<value>COVEREDRATIO</value>");
        assertThat(position("Integration tests and coverage gate")).isPositive();
    }

    @Test
    @Tag("US-601.4")
    @Tag("US-608.2")
    void should_failOnADiagramSyntaxErrorAndUploadTheSvgs_when_diagramsAreRendered() {
        // Act + Assert
        assertThat(ci).contains("-failfast2").contains("docs/diagrams/out/*.svg");
    }

    @Test
    @Tag("US-601.5")
    void should_uploadTestCoverageAndScanReports_when_anyRunFinishes() {
        // Act
        String upload = ci.substring(position("Upload reports"));

        // Assert: always(), even when an earlier step failed
        assertThat(upload).contains("if: always()").contains("target/surefire-reports/")
                .contains("target/failsafe-reports/").contains("target/site/jacoco/").contains("trivy-*.txt");
    }

    @Test
    @Tag("US-602.1")
    void should_failTheBuildOnCriticalFindings_when_dependenciesOrTheImageAreScanned() {
        // Act
        String dependencies = ci.substring(position("Trivy dependency scan"), position("Build Docker image"));
        String image = ci.substring(position("Trivy image scan"));

        // Assert: CRITICAL is CVSS >= 9.0 (ADR-006); the table report names the package and CVE
        for (String scan : List.of(dependencies, image)) {
            assertThat(scan).contains("severity: CRITICAL").contains("exit-code: '1'").contains("format: table");
        }
    }

    @Test
    @Tag("US-602.2")
    void should_reportFindingsBelowCriticalWithoutFailing_when_dependenciesAreScanned() {
        // Act
        String full = ci.substring(position("Trivy full report"));

        // Assert: every severity listed in an uploaded report, never failing the build
        assertThat(full).contains("severity: UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL").contains("exit-code: '0'")
                .contains("output: trivy-full-report.txt");
    }

    @Test
    @Tag("US-606.1")
    void should_startTheWholeStackWithDockerCompose_when_theSmokeJobRuns() {
        // Act + Assert: a CI job runs docker compose and waits for the app's readiness (A-49)
        assertThat(ci).contains("compose-smoke:").contains("scripts/compose-smoke.sh");
        assertThat(read("scripts/compose-smoke.sh")).contains("docker compose up -d --build")
                .contains("/actuator/health/readiness").contains("docker compose down");
    }

    @Test
    @Tag("US-605.3")
    void should_runThePerformanceBaselineOnDemandAndUploadItsSummary_when_dispatched() {
        // Arrange
        assertThat(RepositoryFiles.exists(".github/workflows/performance.yml")).isTrue();
        String performance = read(".github/workflows/performance.yml");

        // Act + Assert: manual trigger only (too slow and noisy as a gate on every PR), summary kept as an artifact
        assertThat(performance).contains("workflow_dispatch:").doesNotContain("pull_request:").doesNotContain("push:")
                .contains("-Pperformance").contains("target/performance/");
        assertThat(pom).contains("<id>performance</id>").contains("**/*PT.java");
    }
}
