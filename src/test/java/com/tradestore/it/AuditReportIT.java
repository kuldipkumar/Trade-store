package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Violation;
import com.tradestore.persistence.AuditEventStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * US-502: the audit report. ITs share one MongoDB, so every test filters on its own unique tradeId; dates are
 * local to the configured zone (A-53).
 */
@IntegrationTest
class AuditReportIT {

    private final RestTestClient rest;
    private final AuditEventStore audit;
    private final Clock clock;

    private final String tradeId = "A-" + UUID.randomUUID();

    AuditReportIT(RestTestClient rest, AuditEventStore audit, Clock clock) {
        this.rest = rest;
        this.audit = audit;
        this.clock = clock;
    }

    private Instant local(String dateTime) {
        return LocalDateTime.parse(dateTime).atZone(clock.getZone()).toInstant();
    }

    private void record(Outcome outcome, String localDateTime) {
        List<Violation> reasons = outcome == Outcome.REJECTED
                ? List.of(Violation.invalidField("bookId", "bookId is required")) : List.of();
        audit.insert(new AuditEvent(UUID.randomUUID().toString(), tradeId, 1, outcome, reasons, Channel.REST,
                local(localDateTime), "{\"tradeId\":\"" + tradeId + "\"}", null));
    }

    private EntityExchangeResult<JsonNode> report(String query) {
        return rest.get().uri("/reports/audit?tradeId=" + tradeId + query).exchange().expectBody(JsonNode.class).returnResult();
    }

    private static List<String> timestamps(JsonNode report) {
        return report.path("entries").valueStream().map(e -> e.path("eventTimestamp").asString()).toList();
    }

    @Test
    @Tag("US-502.1")
    void should_returnOnlyEntriesWithinTheLocalDateRange_when_fromAndToAreGiven() {
        // Arrange: one just before, two on the boundary days, one just after
        record(Outcome.ACCEPTED, "2026-10-31T23:59:59");
        record(Outcome.ACCEPTED, "2026-11-01T00:00:00");
        record(Outcome.REPLACED, "2026-11-19T23:59:59");
        record(Outcome.REPLACED, "2026-11-20T00:00:00");

        // Act
        EntityExchangeResult<JsonNode> response = report("&from=2026-11-01&to=2026-11-19");

        // Assert: inclusive local dates, in time order
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(timestamps(response.getResponseBody())).containsExactly(
                local("2026-11-01T00:00:00").toString(), local("2026-11-19T23:59:59").toString());
    }

    @Test
    @Tag("US-502.2")
    void should_returnOnlyMatchingEntries_when_filteringOnOutcomeAndTradeId() {
        // Arrange
        record(Outcome.ACCEPTED, "2026-11-19T10:00:00");
        record(Outcome.REJECTED, "2026-11-19T11:00:00");

        // Act
        JsonNode body = report("&outcome=REJECTED").getResponseBody();

        // Assert
        assertThat(body.path("entries")).hasSize(1);
        JsonNode entry = body.path("entries").get(0);
        assertThat(entry.path("tradeId").asString()).isEqualTo(tradeId);
        assertThat(entry.path("outcome").asString()).isEqualTo("REJECTED");
        assertThat(entry.path("reasons").get(0).path("reason").asString()).isEqualTo("INVALID_FIELD");
        assertThat(entry.path("channel").asString()).isEqualTo("REST");
    }

    @Test
    @Tag("US-502.3")
    void should_includeSummaryCountsPerOutcomeForTheFilteredSet_when_aFilterIsApplied() {
        // Arrange
        record(Outcome.ACCEPTED, "2026-11-19T10:00:00");
        record(Outcome.REJECTED, "2026-11-19T11:00:00");
        record(Outcome.REJECTED, "2026-11-19T12:00:00");

        // Act
        JsonNode body = report("").getResponseBody();

        // Assert
        assertThat(body.path("summary").path("ACCEPTED").asLong()).isEqualTo(1);
        assertThat(body.path("summary").path("REJECTED").asLong()).isEqualTo(2);
        assertThat(body.path("summary").has("EXPIRED")).isFalse();
    }

    @Test
    @Tag("US-502.4")
    void should_returnOnePageWithPagingMetadata_when_moreEntriesMatchThanThePageSize() {
        // Arrange
        record(Outcome.ACCEPTED, "2026-11-19T10:00:00");
        record(Outcome.REPLACED, "2026-11-19T11:00:00");
        record(Outcome.REPLACED, "2026-11-19T12:00:00");

        // Act
        JsonNode first = report("&page=0&size=2").getResponseBody();
        JsonNode second = report("&page=1&size=2").getResponseBody();

        // Assert
        assertThat(first.path("entries")).hasSize(2);
        assertThat(second.path("entries")).hasSize(1);
        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("totalPages").asLong()).isEqualTo(2);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(timestamps(second)).containsExactly(local("2026-11-19T12:00:00").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"&page=-1", "&size=0", "&from=2026-11-20&to=2026-11-19", "&from=19-11-2026", "&outcome=MAYBE",
            "&to=+999999999-12-31", "&from=-999999999-01-01"})
    @Tag("US-502.1")
    void should_returnBadRequest_when_aParameterIsInvalid(String query) {
        // Act
        EntityExchangeResult<JsonNode> response = report(query);

        // Assert: the standard error shape
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getResponseBody().path("reasons").get(0).path("reason").asString()).isEqualTo("INVALID_PARAMETER");
    }

    @Test
    @Tag("US-503.1")
    void should_returnACsvWithAHeaderAndOneRowPerEntry_when_csvIsAccepted() {
        // Arrange
        record(Outcome.ACCEPTED, "2026-11-19T10:00:00");
        audit.insert(new AuditEvent(UUID.randomUUID().toString(), tradeId, 2, Outcome.REJECTED,
                List.of(Violation.lowerVersion(2, 3), Violation.invalidField("bookId", "bookId is required, got \"\"")),
                Channel.STREAM, local("2026-11-19T11:00:00"), "{}", null));

        // Act
        EntityExchangeResult<String> response = rest.get().uri("/reports/audit?tradeId=" + tradeId)
                .accept(MediaType.parseMediaType("text/csv")).exchange().expectBody(String.class).returnResult();

        // Assert: oldest first; the payload is not exported; RFC 4180 quoting
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(response.getResponseHeaders().getContentType().toString()).startsWith("text/csv");
        List<String> lines = response.getResponseBody().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("eventId,eventTimestamp,tradeId,version,outcome,channel,reasons");
        assertThat(lines.get(1)).endsWith("," + local("2026-11-19T10:00:00") + "," + tradeId + ",1,ACCEPTED,REST,");
        assertThat(lines.get(2)).endsWith("," + local("2026-11-19T11:00:00") + "," + tradeId
                + ",2,REJECTED,STREAM,\"LOWER_VERSION: version 2 is lower than stored version 3;"
                + "INVALID_FIELD: bookId is required, got \"\"\"\"\"");
    }

    @Test
    @Tag("US-503.1")
    void should_returnBadRequest_when_aCsvExportHasAnInvalidRange() {
        // Act
        EntityExchangeResult<byte[]> response = rest.get().uri("/reports/audit?from=2026-11-20&to=2026-11-19")
                .accept(MediaType.parseMediaType("text/csv")).exchange().expectBody().returnResult();

        // Assert: the client error is reported, not hidden behind a 406 or 500
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
