package com.tradestore.domain;

import static com.tradestore.TestTrades.withVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** US-104.1 and US-104.4: one exception per rejected trade, carrying every reason. */
class TradeRejectedExceptionTest {

    private static final Optional<StoredTrade> STORED_V2 = Optional.of(new StoredTrade("T1", 2, false));
    private static final Violation LOWER = new Violation(ReasonCode.LOWER_VERSION, "version", "version 1 is lower than stored version 2");
    private static final Violation PAST = new Violation(ReasonCode.MATURITY_IN_PAST, "maturityDate", "maturityDate is in the past");
    private static final Violation BLANK_BOOK = Violation.invalidField("bookId", "bookId is required");

    @Test
    void should_beLowerVersionException_when_lowerVersionIsTheOnlyReason() {
        // Act
        TradeRejectedException e = TradeRejectedException.of(withVersion(1), STORED_V2, List.of(LOWER));

        // Assert
        assertThat(e).isInstanceOf(LowerVersionException.class);
        LowerVersionException lower = (LowerVersionException) e;
        assertThat(lower.incomingVersion()).isEqualTo(1);
        assertThat(lower.storedVersion()).isEqualTo(2);
        assertThat(lower.getMessage()).isEqualTo("Trade T1 version 1 is lower than stored version 2");
    }

    @Test
    void should_beOneLowerVersionExceptionCarryingAllReasons_when_lowerVersionAndPastMaturity() {
        // Act (US-201.4)
        TradeRejectedException e = TradeRejectedException.of(withVersion(1), STORED_V2, List.of(LOWER, PAST));

        // Assert
        assertThat(e).isInstanceOf(LowerVersionException.class);
        assertThat(e.violations()).extracting(Violation::reason)
                .containsExactly(ReasonCode.LOWER_VERSION, ReasonCode.MATURITY_IN_PAST);
        assertThat(e.reasons()).containsExactly(ReasonCode.LOWER_VERSION, ReasonCode.MATURITY_IN_PAST);
    }

    @Test
    void should_bePlainRejection_when_lowerVersionIsNotAReason() {
        // Act
        TradeRejectedException e = TradeRejectedException.of(withVersion(1), Optional.empty(), List.of(BLANK_BOOK, PAST));

        // Assert
        assertThat(e).isExactlyInstanceOf(TradeRejectedException.class);
        assertThat(e.tradeId()).isEqualTo("T1");
        assertThat(e.getMessage()).isEqualTo("Trade T1 rejected: INVALID_FIELD (bookId), MATURITY_IN_PAST (maturityDate)");
    }

    @Test
    void should_failFast_when_thereAreNoViolations() {
        // Act + Assert
        assertThatIllegalArgumentException()
                .isThrownBy(() -> TradeRejectedException.of(withVersion(1), Optional.empty(), List.of()));
    }

    @Test
    void should_notExposeAMutableViolationList_when_created() {
        // Arrange
        java.util.ArrayList<Violation> violations = new java.util.ArrayList<>(List.of(PAST));

        // Act
        TradeRejectedException e = TradeRejectedException.of(withVersion(1), Optional.empty(), violations);
        violations.add(BLANK_BOOK);

        // Assert
        assertThat(e.violations()).containsExactly(PAST);
    }
}
