package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.withMaturityDate;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-201: a maturity date before today (2026-11-19 in TestTrades) is rejected; today itself is valid (A-13). */
class MaturityNotInPastValidatorTest {

    private final MaturityNotInPastValidator validator = new MaturityNotInPastValidator();

    @Test
    @Tag("US-201.1")
    void should_reportMaturityInPast_when_maturityDateIsBeforeToday() {
        // Act
        List<Violation> violations = validator.validate(contextFor(withMaturityDate("2026-11-18")));

        // Assert
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo(ReasonCode.MATURITY_IN_PAST);
            assertThat(v.field()).isEqualTo("maturityDate");
            assertThat(v.message()).contains("2026-11-18").contains("2026-11-19");
        });
    }
}
