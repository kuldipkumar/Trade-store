package com.tradestore.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.service.AuditReport;

/** The audit report (docs/05-lld.md section 6.1): counts per outcome for the filtered set and one page of entries. */
public record AuditReportResponse(Map<Outcome, Long> summary, List<Entry> entries, int page, int size,
                                  long totalElements, long totalPages) {

    /** One audit entry. {@code error} is set only for POISON and FAILED. */
    public record Entry(String eventId, String tradeId, Integer version, Outcome outcome,
                        List<ApiErrorResponse.Reason> reasons, Channel channel, Instant eventTimestamp, String payload,
                        String error) {

        static Entry from(AuditEvent e) {
            return new Entry(e.eventId(), e.tradeId(), e.version(), e.outcome(),
                    e.reasons().stream().map(ApiErrorResponse.Reason::from).toList(), e.channel(), e.eventTimestamp(),
                    e.payload(), e.error());
        }
    }

    public static AuditReportResponse from(AuditReport report) {
        return new AuditReportResponse(report.summary(), report.entries().stream().map(Entry::from).toList(),
                report.page(), report.size(), report.totalElements(), report.totalPages());
    }
}
