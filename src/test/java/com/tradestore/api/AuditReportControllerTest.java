package com.tradestore.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradestore.service.AuditReportService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
}
