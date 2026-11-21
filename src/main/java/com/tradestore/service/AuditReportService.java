package com.tradestore.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.domain.Outcome;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.AuditQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** The audit report (US-502): filters by local date range, trade and outcome; paged, with summary counts. */
@Service
public class AuditReportService {

    private final AuditEventStore audit;
    private final Clock clock;
    private final int maxPageSize;

    @Autowired
    public AuditReportService(AuditEventStore audit, Clock clock, TradeStoreProperties properties) {
        this(audit, clock, properties.api().maxPageSize());
    }

    AuditReportService(AuditEventStore audit, Clock clock, int maxPageSize) {
        this.audit = audit;
        this.clock = clock;
        this.maxPageSize = maxPageSize;
    }

    /**
     * @param from first local date included (in the configured zone, A-53); null for no lower bound
     * @param to   last local date included; null for no upper bound
     */
    public AuditReport report(LocalDate from, LocalDate to, String tradeId, Outcome outcome, int page, int size) {
        ZoneId zone = clock.getZone();
        AuditQuery query = new AuditQuery(from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), tradeId, outcome);
        Map<Outcome, Long> summary = audit.countByOutcome(query);
        long total = summary.values().stream().mapToLong(Long::longValue).sum();
        return new AuditReport(summary, audit.find(query, page, size), page, size, total, (total + size - 1) / size);
    }
}
