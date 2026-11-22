package com.tradestore.repo;

import static com.tradestore.repo.RepositoryFiles.exists;
import static com.tradestore.repo.RepositoryFiles.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A-47, A-48: static (CodeQL) and dynamic (OWASP ZAP) security scans run in CI, besides the Trivy SCA gate. */
class SecurityScanConformanceTest {

    @Test
    @Tag("A-48")
    void should_runCodeQlStaticAnalysis_when_codeChangesOnMain() {
        // Arrange
        assertThat(exists(".github/workflows/codeql.yml")).isTrue();

        // Act
        String codeql = read(".github/workflows/codeql.yml");

        // Assert: SAST on pull requests, pushes to main and weekly; results in the Security tab
        assertThat(codeql).contains("pull_request:").contains("push:").contains("schedule:")
                .contains("github/codeql-action/init@").contains("languages: java-kotlin")
                .contains("github/codeql-action/analyze@").contains("security-events: write");
    }

    @Test
    @Tag("A-47")
    void should_runAnOwaspZapApiScanAgainstTheRunningStack_when_theSmokeJobRuns() {
        // Act
        String ci = read(".github/workflows/ci.yml");

        // Assert: DAST against the real stack, driven by the OpenAPI contract; report uploaded; High risk fails
        assertThat(ci).contains("zap-api-scan.py").contains("docs/api/openapi.yaml")
                .contains("zap-report.html").contains("scripts/zap_gate.py");
        assertThat(read("scripts/compose-smoke.sh")).contains("KEEP_UP");
    }

    @Test
    @Tag("A-47")
    void should_describeEveryEndpoint_when_theOpenApiContractIsRead() {
        // Act
        String openapi = read("docs/api/openapi.yaml");

        // Assert: the contract the DAST scan exercises covers every public endpoint
        for (String path : List.of("/trades:", "/trades/{tradeId}:", "/trades/{tradeId}/history:", "/reports/audit:")) {
            assertThat(openapi).as(path).contains("  " + path);
        }
        assertThat(openapi).contains("openapi: 3.").contains("text/csv").contains("ApiErrorResponse");
    }
}
