package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.withMaturityDate;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** US-105.3: a missing maturityDate, or one not in yyyy-MM-dd format, is rejected with INVALID_FIELD (A-04). */
class MaturityDateFormatValidatorTest {

    private final MaturityDateFormatValidator validator = new MaturityDateFormatValidator();

    @ParameterizedTest
    @ValueSource(strings = {"2027-05-20", "2028-02-29", "2015-03-14"})
    @Tag("US-105.3")
    void should_reportNoViolation_when_dateIsAValidIsoDate(String maturityDate) {
        // Act (a past date is a different rule: MaturityNotInPastValidator)
        List<Violation> violations = validator.validate(contextFor(withMaturityDate(maturityDate)));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "20/05/2027", "2027/05/20", "2027-5-20", "2027-05-20T00:00",
            "2027-02-30", "2027-13-01", "2027-02-29", "tomorrow", " 2027-05-20"})
    @Tag("US-105.3")
    void should_reportInvalidMaturityDate_when_dateIsMissingOrNotStrictIso(String maturityDate) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withMaturityDate(maturityDate)));

        // Assert
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo(ReasonCode.INVALID_FIELD);
            assertThat(v.field()).isEqualTo("maturityDate");
        });
    }
}
