package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * A-46 / ADR-012: one trace follows a trade from the Kafka producer through ingestion and PostgreSQL to the outbox
 * relay and MongoDB; log lines and latency exemplars carry its trace ID.
 */
@IntegrationTest
class TracingIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AttributeKey<String> TRADE_ID = AttributeKey.stringKey("trade.id");

    private final KafkaTemplate<String, String> kafka;
    private final InMemorySpanExporter spans;
    private final InMemoryLogRecordExporter logs;
    private final RestTestClient rest;
    private final ApplicationContext context;

    private final String tradeId = "TR-" + UUID.randomUUID();

    TracingIT(KafkaTemplate<String, String> kafka, InMemorySpanExporter spans, InMemoryLogRecordExporter logs, RestTestClient rest,
              ApplicationContext context) {
        this.context = context;
        this.kafka = kafka;
        this.spans = spans;
        this.logs = logs;
        this.rest = rest;
    }

    @BeforeEach
    void clearExporters() {
        // The exporters are shared by every IT context; start each test from empty (code review of PR #72)
        spans.reset();
        logs.reset();
    }

    private void publish() throws Exception {
        kafka.send("trades.inbound", tradeId, """
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(tradeId)).get();
    }

    private SpanData ingestSpan() {
        return await().atMost(TIMEOUT).until(() -> spans.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("trade.ingest") && tradeId.equals(s.getAttributes().get(TRADE_ID)))
                .findFirst(), Optional::isPresent).orElseThrow();
    }

    private List<SpanData> trace(String traceId) {
        return spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
    }

    private static String dbSystem(SpanData span) {
        String system = span.getAttributes().get(AttributeKey.stringKey("db.system"));
        return system != null ? system : span.getAttributes().get(AttributeKey.stringKey("db.system.name"));
    }

    @Test
    @Tag("A-46")
    void should_continueTheProducersTraceInTheConsumer_when_aTradeIsPublishedWithKafkaTemplate() throws Exception {
        // Act
        publish();
        SpanData ingest = ingestSpan();

        // Assert: traceparent travelled in the message headers, so producer and consumer share one trace
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trace(ingest.getTraceId()))
                .extracting(SpanData::getKind).contains(SpanKind.PRODUCER, SpanKind.CONSUMER));
        SpanData consumer = trace(ingest.getTraceId()).stream().filter(s -> s.getKind() == SpanKind.CONSUMER).findFirst().orElseThrow();
        assertThat(ingest.getParentSpanId()).isEqualTo(consumer.getSpanId());
    }

    @Test
    @Tag("A-46")
    void should_recordAnIngestSpanWithTheTradeIdentityAndOutcome_when_aTradeIsConsumed() throws Exception {
        // Act
        publish();
        SpanData ingest = ingestSpan();

        // Assert: searchable in Tempo by trade (TraceQL { span.trade.id = "..." })
        assertThat(ingest.getAttributes().get(AttributeKey.stringKey("trade.version"))).isEqualTo("1");
        assertThat(ingest.getAttributes().get(AttributeKey.stringKey("trade.channel"))).isEqualTo("STREAM");
        assertThat(ingest.getAttributes().get(AttributeKey.stringKey("trade.outcome"))).isEqualTo("ACCEPTED");
    }

    @Test
    @Tag("A-46")
    void should_recordPostgresStatementsInsideTheIngestSpan_when_aTradeIsStored() throws Exception {
        // Act
        publish();
        SpanData ingest = ingestSpan();

        // Assert: the SELECT, upsert and outbox INSERT are children of the ingest span
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(trace(ingest.getTraceId()).stream()
                .filter(s -> "postgresql".equals(dbSystem(s)) && s.getParentSpanId().equals(ingest.getSpanId())).count())
                .isGreaterThanOrEqualTo(3));
    }

    @Test
    @Tag("A-46")
    void should_continueTheTradesTraceInTheOutboxRelay_when_theAuditIsWrittenToMongo() throws Exception {
        // Act
        publish();
        SpanData ingest = ingestSpan();

        // Assert: the relay (another thread, about a second later) joins the same trace; its MongoDB inserts are children
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<SpanData> trace = trace(ingest.getTraceId());
            Optional<SpanData> relaySpan = trace.stream().filter(s -> s.getName().equals("outbox.relay")).findFirst();
            assertThat(relaySpan).isPresent();
            SpanData relay = relaySpan.get();
            assertThat(trace.stream().filter(s -> "mongodb".equals(dbSystem(s)) && s.getParentSpanId().equals(relay.getSpanId())))
                    .hasSizeGreaterThanOrEqualTo(2);   // audit_events and trade_history
        });
    }

    @Test
    @Tag("A-46")
    @ExtendWith(OutputCaptureExtension.class)
    void should_stampTheTraceIdOnTheOutcomeLogLine_when_aTradeIsProcessed(CapturedOutput output) throws Exception {
        // Act
        publish();
        SpanData ingest = ingestSpan();

        // Assert: the console line and the exported OTLP log record both carry the trace
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(output.getOut().lines()
                .filter(l -> l.contains("outcome=ACCEPTED tradeId=" + tradeId))).singleElement().asString().contains(ingest.getTraceId()));
        await().atMost(TIMEOUT).untilAsserted(() -> {
            // assertThat, not orElseThrow: Awaitility retries assertion errors only
            Optional<LogRecordData> record = logs.getFinishedLogRecordItems().stream()
                    .filter(r -> r.getBodyValue() != null && r.getBodyValue().asString().contains("tradeId=" + tradeId)).findFirst();
            assertThat(record).isPresent();
            assertThat(record.get().getSpanContext().getTraceId()).isEqualTo(ingest.getTraceId());
        });
    }

    @Test
    @Tag("A-46")
    void should_attachTraceExemplarsToTheLatencyHistogram_when_metricsAreScraped() throws Exception {
        // Arrange
        publish();
        ingestSpan();

        // Act: the OpenMetrics format Prometheus asks for
        String metrics = rest.get().uri("/actuator/prometheus").header("Accept", "application/openmetrics-text;version=1.0.0")
                .exchange().expectBody(String.class).returnResult().getResponseBody();

        // Assert: a latency bucket links to a trace, so Grafana can jump from a slow point to its trace
        assertThat(metrics.lines().filter(l -> l.startsWith("trade_processing_seconds_bucket") && l.contains("trace_id=\"")))
                .isNotEmpty();
    }

    @Test
    @Tag("A-46")
    void should_notPushMetricsOverOtlp_when_prometheusAlreadyScrapesThem() {
        // Act + Assert: the OpenTelemetry starter brings an OTLP metrics registry that pushes to localhost:4318 every
        // minute and logs a WARN when nothing listens; metrics stay on Prometheus only
        assertThat(context.getBeanNamesForType(io.micrometer.registry.otlp.OtlpMeterRegistry.class)).isEmpty();
    }

    @Test
    @Tag("A-46")
    void should_notTraceIdleOutboxPollsOrMetricsScrapes_when_nothingHappens() {
        // Act: several relay polls (every second) and a Prometheus scrape, with no trade (code review of PR #72)
        rest.get().uri("/actuator/prometheus").exchange();
        await().pollDelay(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6)).until(() -> true);

        // Assert: no empty traces crowd out the trade traces in Tempo
        assertThat(spans.getFinishedSpanItems()).filteredOn(s -> !s.getParentSpanContext().isValid())
                .extracting(SpanData::getName).isEmpty();
    }

    @Test
    @Tag("A-46")
    void should_notMarkTheIngestSpanAsAnError_when_aTradeIsRejectedOnTheStream() throws Exception {
        // Arrange: version 2 stored, then version 1 (lower version, a business rejection)
        kafka.send("trades.inbound", tradeId, """
                {"tradeId":"%s","version":2,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20"}"""
                .formatted(tradeId)).get();
        publish();

        // Act
        SpanData rejected = await().atMost(TIMEOUT).until(() -> spans.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("trade.ingest") && tradeId.equals(s.getAttributes().get(TRADE_ID))
                        && "REJECTED".equals(s.getAttributes().get(AttributeKey.stringKey("trade.outcome"))))
                .findFirst(), Optional::isPresent).orElseThrow();

        // Assert
        assertThat(rejected.getStatus().getStatusCode()).isNotEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }
}
