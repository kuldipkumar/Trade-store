package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.Violation;

/** US-105.3: maturityDate must be present and a real date in yyyy-MM-dd format (A-04). */
public class MaturityDateFormatValidator implements TradeValidator {

    @Override
    public List<Violation> validate(ValidationContext context) {
        if (MaturityDates.parse(context.submission().maturityDate()).isEmpty()) {
            return List.of(Violation.invalidField("maturityDate", "maturityDate must be a valid date in yyyy-MM-dd format"));
        }
        return List.of();
    }
}
