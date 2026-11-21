package com.tradestore.service.validation;

import java.util.ArrayList;
import java.util.List;

import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * US-105.1: tradeId, counterPartyId and bookId must be present, not blank, fit the 64-character columns, and
 * contain no control characters (which PostgreSQL would otherwise reject later as a database error).
 */
@Component
@Order(100)
public class MandatoryFieldsValidator implements TradeValidator {

    static final int MAX_ID_LENGTH = 64;

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
        if (value == null || value.isBlank()) {
            violations.add(Violation.invalidField(field, field + " is required"));
        } else if (value.length() > MAX_ID_LENGTH) {
            violations.add(Violation.invalidField(field, field + " must be at most " + MAX_ID_LENGTH + " characters"));
        } else if (value.chars().anyMatch(Character::isISOControl)) {
            violations.add(Violation.invalidField(field, field + " must not contain control characters"));
        }
    }
}
