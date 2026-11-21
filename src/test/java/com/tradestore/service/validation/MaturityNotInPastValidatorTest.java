package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.withMaturityDate;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-19", "2026-11-20", "2027-05-20"})
    @Tag("US-201.2")
    void should_reportNoViolation_when_maturityDateIsTodayOrLater(String maturityDate) {
        // Act (the boundary: a maturity date equal to today is valid, A-13)
        List<Violation> violations = validator.validate(contextFor(withMaturityDate(maturityDate)));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "18/11/2026", "2026-02-30"})
    @Tag("US-104.4")
    void should_skipCheck_when_maturityDateIsItselfInvalid(String maturityDate) {
        // Act: MaturityDateFormatValidator reports this; no misleading second reason
        List<Violation> violations = validator.validate(contextFor(withMaturityDate(maturityDate)));

        // Assert
        assertThat(violations).isEmpty();
    }
}
