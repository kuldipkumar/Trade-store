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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.core.ConditionTimeoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * US-605: performance baseline (A-41) on a single instance with Testcontainers, driven by the shared load file
 * loadtest/trades-10k.csv (the same file scripts/load_trades.py streams in demos). Runs only with
 * {@code -Pperformance} (the *PT suffix keeps it out of the normal suite); the summary goes to
 * target/performance/summary.md, uploaded by the on-demand workflow.
 */
@IntegrationTest
class PerformanceBaselinePT {

    private static final Path SUMMARY = Path.of("target/performance/summary.md");
    private static final Path TRADES = Path.of("loadtest/trades-10k.csv");
    private static final int RATE_PER_SECOND = 50;
    private static final int SECONDS = 60;

    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry registry;
    private final int port;

    PerformanceBaselinePT(KafkaTemplate<String, String> kafka, MeterRegistry registry, @LocalServerPort int port) {
        this.kafka = kafka;
        this.registry = registry;
        this.port = port;
    }

    /** One row of loadtest/trades-10k.csv (scripts/generate_trades.py) with the outcome the store must produce. */
    record Row(String tradeId, String version, String counterPartyId, String bookId, String maturityDate,
               String expectedOutcome, String expectedReason, String raw) {

        String key(String prefix) {
            return prefix + tradeId;
        }

        String value(String prefix) {
            return "POISON".equals(expectedOutcome) ? raw : """
                    {"tradeId":"%s","version":%s,"counterPartyId":"%s","bookId":"%s","maturityDate":"%s"}"""
                    .formatted(key(prefix), version, counterPartyId, bookId, maturityDate);
        }

        /** The REST status for this row's outcome (POISON is a malformed body over REST: 400). */
        int restStatus() {
            return switch (expectedOutcome + " " + expectedReason) {
                case "ACCEPTED " -> 201;
                case "REPLACED " -> 200;
                case "REJECTED LOWER_VERSION" -> 409;
                case "REJECTED MATURITY_IN_PAST" -> 422;
                default -> 400;
            };
        }
    }

    private static List<Row> rows() throws IOException {
        return Files.readAllLines(TRADES).stream().skip(1).map(line -> line.split(",", -1))
                .map(c -> new Row(c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8])).toList();
    }

    /** Trades processed so far, by outcome: the outcome counters plus dead letters (each row has at most one reason). */
    private Map<String, Long> processed() {
        Map<String, Long> counts = new TreeMap<>();
        for (String outcome : List.of("ACCEPTED", "REPLACED", "REJECTED")) {
            counts.put(outcome, (long) registry.find("trade.outcomes").tag("outcome", outcome).counters().stream()
                    .mapToDouble(Counter::count).sum());
        }
        counts.put("POISON", (long) registry.find("trade.deadletter").tag("type", "POISON").counters().stream()
                .mapToDouble(Counter::count).sum());
        return counts;
    }

    private static Map<String, Long> delta(Map<String, Long> after, Map<String, Long> before) {
        Map<String, Long> d = new TreeMap<>();
        after.forEach((k, v) -> d.put(k, v - before.getOrDefault(k, 0L)));
        return d;
    }

    private static long total(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private static void summarise(String section) throws IOException {
        Files.createDirectories(SUMMARY.getParent());
        Files.writeString(SUMMARY, section + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println(section);
    }

    /** The JDK client's message when a reused keep-alive connection was closed by the server before replying. */
    private static boolean isStaleConnection(IOException failure) {
        return failure.getMessage() != null && failure.getMessage().contains("header parser received no bytes");
    }

    @Test
    @Tag("US-605.1")
    @Tag("US-605.3")
    void should_processEveryTradeOfTheFileWithin60Seconds_when_all10000ArePublishedAtOnce() throws Exception {
        // Arrange: the shared load file, with fresh trade IDs for this run
        List<Row> rows = rows();
        Map<String, Long> expected = new TreeMap<>(rows.stream().collect(
                java.util.stream.Collectors.groupingBy(Row::expectedOutcome, java.util.stream.Collectors.counting())));
        String prefix = "PERF-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        Map<String, Long> before = processed();
        long start = System.nanoTime();

        // Act: publish every row keyed by tradeId, then wait until every one has an outcome
        List<CompletableFuture<?>> sends = new ArrayList<>(rows.size());
        for (Row row : rows) {
            sends.add(kafka.send("trades.inbound", row.key(prefix), row.value(prefix)));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        try {
            await().atMost(Duration.ofSeconds(SECONDS)).pollInterval(Duration.ofMillis(250))
                    .until(() -> total(delta(processed(), before)) >= rows.size());
        } catch (ConditionTimeoutException tooSlow) {
            // Fall through: the summary below records how far it got, then the assertions fail
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        Map<String, Long> actual = delta(processed(), before);

        // Assert: summary first, so a miss still leaves evidence; then zero loss and the exact outcome of every row
        summarise("""
                ## US-605.1 Stream burst (loadtest/trades-10k.csv)
                | Messages | Processed | Seconds | Throughput (msg/s) | Outcomes (expected → actual) | Target |
                |---|---|---|---|---|---|
                | %d | %d | %.1f | %.0f | %s → %s | all within 60 s, zero loss |
                """.formatted(rows.size(), total(actual), seconds, total(actual) / seconds, expected, actual));
        assertThat(actual).isEqualTo(expected);
        assertThat(seconds).isLessThan(SECONDS);
    }

    @Test
    @Tag("US-605.2")
    @Tag("US-605.3")
    void should_keepP95LatencyUnder200ms_when_restSubmissionsRunAt50PerSecondFor60Seconds() throws Exception {
        // Arrange: the first 3,000 rows of the shared load file, with fresh trade IDs
        String prefix = "PERF-REST-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        List<Row> rows = rows();
        Map<String, CompletableFuture<Void>> lastPerTrade = new java.util.HashMap<>();
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
                Row row = rows.get(i);
                long slot = due;
                Runnable call = () -> {
                    // A chained row still waits for its own 20 ms slot, so the load stays open loop
                    long early = slot - System.nanoTime();
                    if (early > 0) {
                        java.util.concurrent.locks.LockSupport.parkNanos(early);
                    }
                    boolean retried = false;
                    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/trades"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(row.value(prefix))).build();
                    long sent = System.nanoTime();
                    try {
                        HttpResponse<Void> response;
                        try {
                            response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        } catch (IOException failed) {
                            if (!isStaleConnection(failed)) {
                                throw failed;
                            }
                            // Keep-alive race: the server closed a pooled connection as the request went out. Retried
                            // once like any HTTP client. If the first attempt did arrive, the retry is a same-version
                            // resend, so a new trade answers 200 instead of 201; that is accepted below.
                            retries.incrementAndGet();
                            retried = true;
                            response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        }
                        latenciesMicros.add((System.nanoTime() - sent) / 1_000);
                        boolean resentNewTrade = retried && row.restStatus() == 201 && response.statusCode() == 200;
                        if (response.statusCode() != row.restStatus() && !resentNewTrade) {
                            errors.incrementAndGet();
                            errorKinds.add(row.expectedOutcome() + " " + row.expectedReason() + ": expected "
                                    + row.restStatus() + ", got " + response.statusCode());
                        }
                    } catch (IOException | InterruptedException | RuntimeException e) {
                        // Any failure is counted, never thrown: a thrown error would break the trade's chain and
                        // abort the test before the summary is written (code review of PR #71)
                        errors.incrementAndGet();
                        errorKinds.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                };
                // A trade's versions go one after another (as keyed messages would); different trades overlap
                lastPerTrade.compute(row.key(prefix), (key, previous) -> previous == null
                        ? CompletableFuture.runAsync(call, workers) : previous.thenRunAsync(call, workers));
            }
            // Every chained call must be submitted before the executor closes
            CompletableFuture.allOf(lastPerTrade.values().toArray(CompletableFuture[]::new)).join();
        }

        // Assert: p95 under 200 ms and no failed request (A-41)
        List<Long> sorted = latenciesMicros.stream().sorted().toList();
        if (sorted.isEmpty()) {
            summarise("## US-605.2 REST: no request succeeded. Errors: " + errorKinds.stream().distinct().limit(5).toList());
        }
        assertThat(sorted).as("successful requests").isNotEmpty();
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
        assertThat(retries.get()).as("stale-connection retries").isLessThanOrEqualTo(total / 100);
    }
}
