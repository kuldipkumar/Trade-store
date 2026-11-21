package com.tradestore.service.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.tradestore.domain.Channel;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The validators are discovered as Spring beans and run in a fixed order: field checks first, then the
 * version rule (A-15, US-104.4). Uses a plain Spring context scanning only the validation package.
 */
class ValidatorWiringTest {

    @Test
    @Tag("US-104.4")
    void should_runEveryRegisteredRuleInOrder_when_compositeComesFromTheContext() {
        try (var context = new AnnotationConfigApplicationContext(ValidatorWiringTest.class.getPackageName())) {
            // Arrange: blank bookId, bad date, and a lower version than stored
            CompositeTradeValidator validator = context.getBean(CompositeTradeValidator.class);
            TradeSubmission submission = new TradeSubmission("T1", 1, "CP-1", " ", "2027-02-30", Channel.REST, "{}");
            ValidationContext ctx = new ValidationContext(submission, Optional.of(new StoredTrade("T1", 2, false)),
                    LocalDate.of(2026, 11, 19));

            // Act
            List<Violation> violations = validator.validate(ctx);

            // Assert
            assertThat(violations).extracting(Violation::reason, Violation::field).containsExactly(
                    tuple(ReasonCode.INVALID_FIELD, "bookId"),
                    tuple(ReasonCode.INVALID_FIELD, "maturityDate"),
                    tuple(ReasonCode.LOWER_VERSION, "version"));
        }
    }

    @Test
    @Tag("US-201.4")
    void should_reportMaturityAfterVersion_when_bothRulesAreBroken() {
        try (var context = new AnnotationConfigApplicationContext(ValidatorWiringTest.class.getPackageName())) {
            // Arrange: a lower version that also matures before today
            CompositeTradeValidator validator = context.getBean(CompositeTradeValidator.class);
            TradeSubmission submission = new TradeSubmission("T1", 1, "CP-1", "B1", "2026-11-18", Channel.REST, "{}");
            ValidationContext ctx = new ValidationContext(submission, Optional.of(new StoredTrade("T1", 2, false)),
                    LocalDate.of(2026, 11, 19));

            // Act
            List<Violation> violations = validator.validate(ctx);

            // Assert
            assertThat(violations).extracting(Violation::reason)
                    .containsExactly(ReasonCode.LOWER_VERSION, ReasonCode.MATURITY_IN_PAST);
        }
    }
}
