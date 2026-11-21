package com.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.tradestore.domain.Outcome;
import com.tradestore.persistence.AuditEventStore;
import com.tradestore.persistence.AuditQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-502: the report's date range, totals and page-size cap. */
class AuditReportServiceTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private final AuditEventStore audit = mock(AuditEventStore.class);
    private final AuditReportService service = new AuditReportService(audit,
            Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), KOLKATA), 100);

    @BeforeEach
    void emptyStore() {
        when(audit.find(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(audit.countByOutcome(any())).thenReturn(Map.of());
    }

    @Test
    @Tag("US-502.1")
    void should_queryFromStartOfFromDateUntilStartOfTheDayAfterTo_when_localDatesAreGiven() {
        // Act
        service.report(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 19), "T2", Outcome.REJECTED, 0, 50);

        // Assert: inclusive local dates in the configured zone (A-53), as a half-open instant range
        AuditQuery expected = new AuditQuery(Instant.parse("2026-10-31T18:30:00Z"), Instant.parse("2026-11-19T18:30:00Z"),
                "T2", Outcome.REJECTED);
        verify(audit).find(expected, 0, 50);
        verify(audit).countByOutcome(expected);
    }
}
