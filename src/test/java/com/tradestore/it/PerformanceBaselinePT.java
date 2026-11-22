package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * US-605: performance baseline (A-41) on a single instance with Testcontainers. Runs only with
 * {@code -Pperformance} (the *PT suffix keeps it out of the normal suite); the summary goes to
 * target/performance/summary.md, uploaded by the on-demand workflow.
 */
@IntegrationTest
class PerformanceBaselinePT {

    private static final Path SUMMARY = Path.of("target/performance/summary.md");
    private static final int BURST = 10_000;
    private static final int RATE_PER_SECOND = 50;
    private static final int SECONDS = 60;

    private final KafkaTemplate<String, String> kafka;
    private final JdbcClient jdbc;
    private final int port;

    PerformanceBaselinePT(KafkaTemplate<String, String> kafka, JdbcClient jdbc, @LocalServerPort int port) {
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.port = port;
    }

    private static String trade(String tradeId) {
        return """
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(tradeId);
    }

    private long stored(String prefix) {
        return jdbc.sql("SELECT count(*) FROM trades WHERE trade_id LIKE ?").param(prefix + "%").query(Long.class).single();
    }

    private static void summarise(String section) throws IOException {
        Files.createDirectories(SUMMARY.getParent());
        Files.writeString(SUMMARY, section + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println(section);
    }

    @Test
    @Tag("US-605.1")
    @Tag("US-605.3")
    void should_storeEveryTradeWithin60Seconds_when_aBurstOf10000IsPublished() throws Exception {
        // Arrange
        String prefix = "PERF-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        long start = System.nanoTime();

        // Act: publish the burst, then wait until every trade is stored
        List<CompletableFuture<?>> sends = new ArrayList<>(BURST);
        for (int i = 0; i < BURST; i++) {
            String tradeId = prefix + i;
            sends.add(kafka.send("trades.inbound", tradeId, trade(tradeId)));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(SECONDS)).pollInterval(Duration.ofMillis(250))
                .until(() -> stored(prefix) == BURST);
        double seconds = (System.nanoTime() - start) / 1e9;

        // Assert: all stored, none lost, within 60 s (A-41)
        assertThat(stored(prefix)).isEqualTo(BURST);
        assertThat(seconds).isLessThan(SECONDS);
        summarise("""
                ## US-605.1 Stream burst
                | Trades | Stored | Seconds | Throughput (trades/s) | Target |
                |---|---|---|---|---|
                | %d | %d | %.1f | %.0f | all within 60 s, zero loss |
                """.formatted(BURST, stored(prefix), seconds, BURST / seconds));
    }

    @Test
    @Tag("US-605.2")
    @Tag("US-605.3")
    void should_keepP95LatencyUnder200ms_when_restSubmissionsRunAt50PerSecondFor60Seconds() throws Exception {
        // Arrange
        String prefix = "PERF-REST-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build(); // no h2c upgrade attempt on plain HTTP
        ConcurrentLinkedQueue<Long> latenciesMicros = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        ConcurrentLinkedQueue<String> errorKinds = new ConcurrentLinkedQueue<>();
        AtomicInteger retries = new AtomicInteger();
        int total = RATE_PER_SECOND * SECONDS;
        long intervalNanos = 1_000_000_000L / RATE_PER_SECOND;

        // Act: an open-loop load (one request every 20 ms, whatever the response times)
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            for (int i = 0; i < total; i++) {
                long due = start + i * intervalNanos;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }
                String tradeId = prefix + i;
                workers.submit(() -> {
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/trades"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(trade(tradeId))).build();
                    long sent = System.nanoTime();
                    try {
                        HttpResponse<Void> response;
                        try {
                            response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        } catch (IOException staleConnection) {
                            // Keep-alive race: the server closed a pooled connection as the request went out, so it
                            // never arrived. Retried once like any HTTP client; safe because the same version replaces.
                            retries.incrementAndGet();
                            response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        }
                        latenciesMicros.add((System.nanoTime() - sent) / 1_000);
                        if (response.statusCode() != 201 && response.statusCode() != 200) {
                            errors.incrementAndGet();
                            errorKinds.add("HTTP " + response.statusCode());
                        }
                    } catch (IOException | InterruptedException e) {
                        errors.incrementAndGet();
                        errorKinds.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            }
        }

        // Assert: p95 under 200 ms and no failed request (A-41)
        List<Long> sorted = latenciesMicros.stream().sorted().toList();
        double p50 = sorted.get(sorted.size() / 2) / 1000.0;
        double p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1000.0;
        double p99 = sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1) / 1000.0;
        summarise("""
                ## US-605.2 REST at %d requests/s for %d s
                | Requests | Errors | Connection retries | p50 (ms) | p95 (ms) | p99 (ms) | Target |
                |---|---|---|---|---|---|---|
                | %d | %d | %d | %.1f | %.1f | %.1f | p95 < 200 ms |
                %s""".formatted(RATE_PER_SECOND, SECONDS, sorted.size(), errors.get(), retries.get(), p50, p95, p99,
                errorKinds.isEmpty() ? "" : "Errors: " + errorKinds.stream().distinct().limit(5).toList() + "\n"));
        assertThat(errors).hasValue(0);
        assertThat(sorted).hasSize(total);
        assertThat(p95).isLessThan(200.0);
    }
}
