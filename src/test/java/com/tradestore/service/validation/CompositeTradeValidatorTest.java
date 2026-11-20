package com.tradestore.service.validation;

import static com.tradestore.TestTrades.contextFor;
import static com.tradestore.TestTrades.validSubmission;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Test;

/** A-15 / US-104.4: all validators run and every violation is collected, not just the first. */
class CompositeTradeValidatorTest {

    private final CompositeTradeValidator validator = new CompositeTradeValidator(List.of(
            new MandatoryFieldsValidator(),
            new PositiveVersionValidator(),
            new MaturityDateFormatValidator()));

    @Test
    void should_reportNoViolation_when_submissionIsValid() {
        // Act
        List<Violation> violations = validator.validate(contextFor(validSubmission()));

        // Assert
        assertThat(violations).isEmpty();
    }

    @Test
    void should_collectEveryViolation_when_severalRulesAreBroken() {
        // Arrange
        TradeSubmission submission = new TradeSubmission("", 0, "CP-1", null, "2027-02-30", Channel.STREAM, "{}");

        // Act
        List<Violation> violations = validator.validate(contextFor(submission));

        // Assert
        assertThat(violations).extracting(Violation::field)
                .containsExactly("tradeId", "bookId", "version", "maturityDate");
        assertThat(violations).extracting(Violation::reason).containsOnly(ReasonCode.INVALID_FIELD);
    }

    @Test
    void should_runValidatorsInTheirGivenOrder_when_eachReportsAViolation() {
        // Arrange: a rule list in a different order gives the same violations in that order
        CompositeTradeValidator reversed = new CompositeTradeValidator(List.of(
                new MaturityDateFormatValidator(), new MandatoryFieldsValidator()));
        TradeSubmission submission = new TradeSubmission("T1", 1, "CP-1", "", "bad", Channel.REST, "{}");

        // Act
        List<Violation> violations = reversed.validate(contextFor(submission));

        // Assert
        assertThat(violations).extracting(Violation::field).containsExactly("maturityDate", "bookId");
    }
}
