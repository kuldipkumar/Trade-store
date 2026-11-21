package com.tradestore.api;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Stream;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;
import com.tradestore.service.AuditReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** The audit report on demand: JSON, paged (US-502), or CSV, streamed (US-503); same filters. */
@RestController
public class AuditReportController {

    private final AuditReportService reports;

    public AuditReportController(AuditReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/audit")
    public AuditReportResponse report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) String tradeId,
                                      @RequestParam(required = false) Outcome outcome,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        PageParameters.validate(page, size);
        validateRange(from, to);
        return AuditReportResponse.from(reports.report(from, to, tradeId, outcome, page, size));
    }

    /**
     * The whole filtered report as CSV (US-503), streamed from a database cursor row by row, never held in memory
     * (A-52). Not paged: an export is the complete set.
     */
    @GetMapping(path = "/reports/audit", produces = "text/csv")
    public ResponseEntity<StreamingResponseBody> reportCsv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String tradeId,
            @RequestParam(required = false) Outcome outcome) {
        validateRange(from, to);
        // Opened here, not in the body: a database outage then fails the request as a plain 503 (D7) instead of
        // a response already marked as a CSV download.
        Stream<AuditEvent> opened = reports.stream(from, to, tradeId, outcome);
        StreamingResponseBody body = output -> {
            Writer out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            try (Stream<AuditEvent> events = opened) {
                AuditCsvWriter.writeHeader(out);
                for (AuditEvent event : (Iterable<AuditEvent>) events::iterator) {
                    AuditCsvWriter.writeRow(event, out);
                }
            }
            out.flush();
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-report.csv\"")
                .body(body);
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        requireSupportedYear("from", from);
        requireSupportedYear("to", to);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from must not be after to");
        }
    }

    /** ISO allows years far beyond what the date arithmetic and MongoDB dates can hold. */
    private static void requireSupportedYear(String name, LocalDate date) {
        if (date != null && (date.getYear() < 1 || date.getYear() > 9999)) {
            throw new InvalidRequestParameterException(name, name + " must be between 0001-01-01 and 9999-12-31");
        }
    }
}
