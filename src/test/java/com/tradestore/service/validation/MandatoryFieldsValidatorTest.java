package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.validSubmission;
import static com.tradestore.TestTrades.withBookId;
import static com.tradestore.TestTrades.withCounterPartyId;
import static com.tradestore.TestTrades.withTradeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** US-105.1: a blank tradeId, counterPartyId or bookId is rejected with INVALID_FIELD, naming the field. */
class MandatoryFieldsValidatorTest {

    private final MandatoryFieldsValidator validator = new MandatoryFieldsValidator();

    @Test
    @Tag("US-105.1")
    void should_reportNoViolation_when_allMandatoryFieldsArePresent() {
        // Act
        List<Violation> violations = validator.validate(contextFor(validSubmission()));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @Tag("US-105.1")
    void should_reportInvalidTradeId_when_tradeIdIsBlank(String tradeId) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withTradeId(tradeId)));

        // Assert
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo(ReasonCode.INVALID_FIELD);
            assertThat(v.field()).isEqualTo("tradeId");
        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @Tag("US-105.1")
    void should_reportInvalidCounterPartyId_when_counterPartyIdIsBlank(String counterPartyId) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withCounterPartyId(counterPartyId)));

        // Assert
        assertThat(violations).extracting(Violation::reason, Violation::field)
                .containsExactly(tuple(ReasonCode.INVALID_FIELD, "counterPartyId"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @Tag("US-105.1")
    void should_reportInvalidBookId_when_bookIdIsBlank(String bookId) {
        // Act
        List<Violation> violations = validator.validate(contextFor(withBookId(bookId)));

        // Assert
        assertThat(violations).extracting(Violation::reason, Violation::field)
                .containsExactly(tuple(ReasonCode.INVALID_FIELD, "bookId"));
    }

    @Test
    @Tag("US-105.1")
    void should_reportEveryBlankField_when_severalFieldsAreBlank() {
        // Arrange
        TradeSubmission submission = new TradeSubmission(" ", 1, null, "", "2027-05-20", Channel.STREAM, "{}");

        // Act
        List<Violation> violations = validator.validate(contextFor(submission));

        // Assert
        assertThat(violations).extracting(Violation::field)
                .containsExactly("tradeId", "counterPartyId", "bookId");
    }

    @Test
    @Tag("US-105.1")
    void should_reportInvalidField_when_idIsLongerThan64Characters() {
        // Arrange: the database columns are VARCHAR(64) (docs/05-lld.md section 5)
        String tooLong = "T".repeat(65);

        // Act
        List<Violation> violations = validator.validate(contextFor(withTradeId(tooLong)));

        // Assert
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo(ReasonCode.INVALID_FIELD);
            assertThat(v.field()).isEqualTo("tradeId");
            assertThat(v.message()).contains("64");
        });
    }

    @Test
    @Tag("US-105.1")
    void should_reportNoViolation_when_idIsExactly64Characters() {
        // Act
        List<Violation> violations = validator.validate(contextFor(withTradeId("T".repeat(64))));

        // Assert
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A\u0000", "T\n1", "T\t1", "\u007fT1"})
    @Tag("US-105.1")
    void should_reportInvalidField_when_anIdContainsControlCharacters(String tradeId) {
        // Act: e.g. a NUL that PostgreSQL would reject later as a database error
        List<Violation> violations = validator.validate(contextFor(withTradeId(tradeId)));

        // Assert
        assertThat(violations).extracting(Violation::reason, Violation::field)
                .containsExactly(tuple(ReasonCode.INVALID_FIELD, "tradeId"));
    }
}
