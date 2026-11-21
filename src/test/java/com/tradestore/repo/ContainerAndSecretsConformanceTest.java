package com.tradestore.repo;

import static com.tradestore.repo.RepositoryFiles.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-606.2, US-606.3: the image runs as non-root; no credentials are committed (A-47, A-50). */
class ContainerAndSecretsConformanceTest {

    @Test
    @Tag("US-606.2")
    void should_runAsANonRootUser_when_theImageStarts() {
        // Arrange
        String dockerfile = read("Dockerfile");
        String runtimeStage = dockerfile.substring(dockerfile.lastIndexOf("FROM "));

        // Act + Assert: the last stage switches to a non-root user before the entrypoint
        assertThat(runtimeStage).containsPattern("(?m)^USER (?!root\\b|0\\b)\\w+");
        assertThat(runtimeStage.indexOf("USER ")).isLessThan(runtimeStage.indexOf("ENTRYPOINT"));
    }

    @Test
    @Tag("US-606.3")
    void should_takeEveryConnectionSettingFromTheEnvironment_when_theAppIsConfigured() {
        // Act
        String config = read("src/main/resources/application.yml");

        // Assert: each connection value is a ${ENV:local-default} placeholder
        for (String key : List.of("url:", "username:", "password:", "uri:", "bootstrap-servers:")) {
            assertThat(config).containsPattern("(?m)^\\s+" + Pattern.quote(key) + " \\$\\{[A-Z_]+:");
        }
    }

    @Test
    @Tag("US-606.3")
    void should_containNoCommittedSecrets_when_trackedFilesAreScanned() throws IOException, InterruptedException {
        // Arrange: every tracked text file
        Process git = new ProcessBuilder("git", "ls-files").start();
        List<String> files = new String(git.getInputStream().readAllBytes()).lines()
                .filter(f -> !f.endsWith(".svg") && !f.endsWith(".jar") && !f.endsWith(".png")).toList();
        git.waitFor();
        Pattern secret = Pattern.compile(
                "(?i)(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|xox[baprs]-[A-Za-z0-9-]{10,})");

        // Act
        List<String> offenders = files.stream().filter(f -> RepositoryFiles.exists(f) && secret.matcher(read(f)).find()).toList();

        // Assert
        assertThat(files).isNotEmpty();
        assertThat(offenders).isEmpty();
    }
}
