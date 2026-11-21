package com.tradestore.service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Outcome;

/**
 * One page of the audit report (US-502) with counts per outcome for the whole filtered set, in Outcome order.
 *
 * @param totalElements entries matching the filters, across all pages
 */
public record AuditReport(Map<Outcome, Long> summary, List<AuditEvent> entries, int page, int size,
                          long totalElements, long totalPages) {

    public AuditReport {
        summary = Collections.unmodifiableMap(summary.isEmpty() ? new EnumMap<>(Outcome.class) : new EnumMap<>(summary));
        entries = List.copyOf(entries);
    }
}
