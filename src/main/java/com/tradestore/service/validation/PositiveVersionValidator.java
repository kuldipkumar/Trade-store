package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.Violation;

/** US-105.2: version must be present and at least 1 (A-03). */
public class PositiveVersionValidator implements TradeValidator {

    @Override
    public List<Violation> validate(ValidationContext context) {
        Integer version = context.submission().version();
        if (version == null || version < 1) {
            return List.of(Violation.invalidField("version", "version must be 1 or greater"));
        }
        return List.of();
    }
}
