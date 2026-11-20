package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.withVersion;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** US-104.1: a version lower than the stored version is LOWER_VERSION; same or higher is allowed (US-102, US-103). */
class LowerVersionValidatorTest {

    private static final StoredTrade STORED_V2 = new StoredTrade("T1", 2, false);

    private final LowerVersionValidator validator = new LowerVersionValidator();

    @Test
    void should_reportNoViolation_when_noTradeIsStored() {
        // Act
        List<Violation> violations = validator.validate(contextFor(withVersion(1)));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 10})
    void should_reportNoViolation_when_versionIsSameOrHigherThanStored(int version) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withVersion(version), STORED_V2));

        // Assert
        assertThat(violations).isEmpty();
    }

    @Test
    void should_reportLowerVersion_when_versionIsLowerThanStored() {
        // Act
        List<Violation> violations = validator.validate(contextFor(withVersion(1), STORED_V2));

        // Assert
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo(ReasonCode.LOWER_VERSION);
            assertThat(v.field()).isEqualTo("version");
            assertThat(v.message()).contains("version 1").contains("stored version 2");
        });
    }

    @Test
    void should_reportLowerVersion_when_storedTradeIsExpired() {
        // Arrange: expiry does not relax the version rule (A-09 only allows same or higher)
        StoredTrade expiredV2 = new StoredTrade("T1", 2, true);

        // Act
        List<Violation> violations = validator.validate(contextFor(withVersion(1), expiredV2));

        // Assert
        assertThat(violations).extracting(Violation::reason).containsExactly(ReasonCode.LOWER_VERSION);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void should_skipCheck_when_versionIsItselfInvalid(Integer version) {
        // Act: PositiveVersionValidator reports this; no misleading second reason
        List<Violation> violations = validator.validate(contextFor(withVersion(version), STORED_V2));

        // Assert
        assertThat(violations).isEmpty();
    }
}
