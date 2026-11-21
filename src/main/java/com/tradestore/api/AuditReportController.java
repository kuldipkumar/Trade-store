package com.tradestore.api;

import java.time.LocalDate;

import com.tradestore.domain.Outcome;
import com.tradestore.service.AuditReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit report on demand (US-502): filters by local date range, trade and outcome; paged. */
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
        if (page < 0) {
            throw new InvalidRequestParameterException("page", "page must be 0 or greater");
        }
        if (size < 1) {
            throw new InvalidRequestParameterException("size", "size must be 1 or greater");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "from must not be after to");
        }
        return AuditReportResponse.from(reports.report(from, to, tradeId, outcome, page, size));
    }
}
