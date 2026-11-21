package com.tradestore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.service.AuditReportService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** US-503: CSV export failure paths. */
class AuditReportControllerTest {

    private final AuditReportService reports = mock(AuditReportService.class);
    private final AuditReportController controller = new AuditReportController(reports);

    @Test
    @Tag("US-503.1")
    void should_failBeforeAnyCsvHeaderIsSet_when_theStoreIsUnavailable() {
        // Arrange
        when(reports.stream(any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("MongoDB down"));

        // Act + Assert: thrown synchronously, so the handler answers 503 JSON, not an "audit-report.csv" download
        assertThatThrownBy(() -> controller.reportCsv(null, null, null, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    @Tag("US-503.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logAndAbort_when_theStoreFailsPartwayThroughTheExport(CapturedOutput output) {
        // Arrange: one row, then the cursor fails
        AuditEvent row = new AuditEvent("e1", "T1", 1, Outcome.ACCEPTED, List.of(), Channel.REST,
                Instant.parse("2026-11-19T10:00:00Z"), null, null);
        when(reports.stream(any(), any(), any(), any())).thenReturn(Stream.concat(Stream.of(row),
                Stream.generate(() -> { throw new DataAccessResourceFailureException("MongoDB failover"); })));

        // Act + Assert: propagated so the transfer is aborted, never finished as if complete; logged with the count
        assertThatThrownBy(() -> controller.reportCsv(null, null, null, null).getBody()
                .writeTo(new ByteArrayOutputStream())).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(output).contains("ERROR").contains("CSV export failed after 1 rows");
    }

    @Test
    @Tag("US-503.1")
    void should_exportTheErrorAsTheReason_when_anEntryWasDeadLettered() throws Exception {
        // Arrange: FAILED entries carry their cause in error, not in reasons (code review of PR #64)
        StringWriter out = new StringWriter();

        // Act
        AuditCsvWriter.writeRow(new AuditEvent("t-0-1", null, null, Outcome.FAILED, List.of(), Channel.STREAM,
                Instant.parse("2026-11-19T10:00:00Z"), null, null, "DataAccessResourceFailureException: down"), out);

        // Assert
        assertThat(out.toString()).endsWith(",FAILED,STREAM,DataAccessResourceFailureException: down\r\n");
    }
}
