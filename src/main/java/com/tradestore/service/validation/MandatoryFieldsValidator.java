package com.tradestore.service.validation;

import java.util.ArrayList;
import java.util.List;

import com.tradestore.domain.TradeIds;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * US-105.1: tradeId, counterPartyId and bookId must be usable stored identifiers ({@link TradeIds}): present, not
 * blank, within the 64-character columns, without control characters, and valid Unicode. Otherwise PostgreSQL would
 * reject or alter them later, as a database error.
 */
@Component
@Order(100)
public class MandatoryFieldsValidator implements TradeValidator {

    @Override
    public List<Violation> validate(ValidationContext context) {
        TradeSubmission s = context.submission();
        List<Violation> violations = new ArrayList<>();
        check("tradeId", s.tradeId(), violations);
        check("counterPartyId", s.counterPartyId(), violations);
        check("bookId", s.bookId(), violations);
        return violations;
    }

    private static void check(String field, String value, List<Violation> violations) {
        TradeIds.problem(field, value).ifPresent(message -> violations.add(Violation.invalidField(field, message)));
    }
}
