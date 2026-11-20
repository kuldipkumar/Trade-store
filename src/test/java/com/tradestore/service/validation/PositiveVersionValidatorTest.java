package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.withVersion;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** US-105.2: a trade with version 0, a negative version or no version is rejected with INVALID_FIELD (A-03). */
class PositiveVersionValidatorTest {

    private final PositiveVersionValidator validator = new PositiveVersionValidator();

    @ParameterizedTest
    @ValueSource(ints = {1, 2, Integer.MAX_VALUE})
    void should_reportNoViolation_when_versionIsOneOrMore(int version) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withVersion(version)));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void should_reportInvalidVersion_when_versionIsMissingZeroOrNegative(Integer version) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withVersion(version)));

        // Assert
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo(ReasonCode.INVALID_FIELD);
            assertThat(v.field()).isEqualTo("version");
        });
    }
}
