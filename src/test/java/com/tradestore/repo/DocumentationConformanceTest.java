package com.tradestore.repo;

import static com.tradestore.repo.RepositoryFiles.exists;
import static com.tradestore.repo.RepositoryFiles.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-608, US-609: the prompt log, the required diagrams, the README and the dead-letter runbook. */
class DocumentationConformanceTest {

    @Test
    @Tag("US-608.1")
    void should_recordAHumanDecisionForEveryPrompt_when_thePromptLogIsRead() {
        // Arrange: the numbered rows of the table
        List<String> rows = read("PROMPTS.md").lines().filter(l -> l.matches("^\\| \\d+ \\|.*")).toList();

        // Act + Assert: the last column (Accepted / Modified / Rejected and why) is filled in (R-09)
        assertThat(rows).hasSizeGreaterThan(30);
        for (String row : rows) {
            String[] cells = row.split("\\|");
            assertThat(cells[cells.length - 1].isBlank() ? "" : cells[cells.length - 1].trim())
                    .as("decision column of: %s", row.substring(0, Math.min(40, row.length()))).isNotEmpty();
        }
    }

    @Test
    @Tag("US-608.2")
    void should_haveEveryRequiredDiagram_when_theDiagramsFolderIsListed() {
        // Act + Assert: AGENTS.md section 12 (rendering is checked by CI with -failfast2)
        for (String diagram : List.of("03-c4-context", "03-c4-container", "03-deployment", "04-activity-ci-pipeline",
                "05-class-domain", "05-class-components", "05-seq-stream-ingestion", "05-seq-rest-submit",
                "05-state-trade-lifecycle", "05-activity-validation-and-upsert", "05-er-data-model")) {
            assertThat(exists("docs/diagrams/" + diagram + ".puml")).as(diagram).isTrue();
            assertThat(read("docs/diagrams/" + diagram + ".puml")).contains("title");
        }
    }

    @Test
    @Tag("US-609.1")
    void should_coverEveryPhase8Topic_when_theReadmeIsRead() {
        // Act
        String readme = read("README.md");

        // Assert: AGENTS.md Phase 8
        assertThat(readme).contains("## Overview", "## Architecture", "## Run locally", "## Run the tests",
                "## Pipeline", "## Assumptions", "## Known limitations", "## Future improvements")
                .contains("docs/diagrams/out/").doesNotContain("Work in progress");
    }

    @Test
    @Tag("US-609.2")
    void should_guideAnOperatorFromDeadLetterToReplay_when_theRunbookIsFollowed() {
        // Arrange
        assertThat(exists("docs/runbook-dead-letter.md")).as("docs/runbook-dead-letter.md").isTrue();

        // Act
        String runbook = read("docs/runbook-dead-letter.md");

        // Assert: inspect, find the audit entry, fix, replay (A-51, CR-5); linked from the README
        assertThat(runbook).contains("## 1. Inspect the dead-letter topic", "## 2. Find the audit entry",
                "## 3. Fix the cause", "## 4. Replay the message")
                .contains("kafka-console-consumer.sh").contains("/reports/audit?outcome=POISON")
                .contains("kafka_dlt-original-offset").contains("kafka-console-producer.sh");
        assertThat(read("README.md")).contains("docs/runbook-dead-letter.md");
    }
}
