package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.Violation;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link TradeValidator} and collects all violations (A-15), so one rejection carries every
 * reason (US-104.4). Adding a rule means adding a validator class, not editing this one (Open/Closed).
 * Spring injects the validators sorted by {@code @Order}, which fixes the order of reasons: field checks
 * (100-300) before version and maturity rules (400+).
 */
@Component
public class CompositeTradeValidator {

    private final List<TradeValidator> validators;

    public CompositeTradeValidator(List<TradeValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    public List<Violation> validate(ValidationContext context) {
        return validators.stream()
                .flatMap(validator -> validator.validate(context).stream())
                .toList();
    }
}
