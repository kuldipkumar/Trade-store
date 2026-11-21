package com.tradestore.service;

import java.util.List;
import java.util.Map;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;

/**
 * One page of the audit report (US-502) with counts per outcome for the whole filtered set.
 *
 * @param totalElements entries matching the filters, across all pages
 */
public record AuditReport(Map<Outcome, Long> summary, List<AuditEvent> entries, int page, int size,
                          long totalElements, long totalPages) {

    public AuditReport {
        summary = Map.copyOf(summary);
        entries = List.copyOf(entries);
    }
}
