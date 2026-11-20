package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.Violation;
import com.tradestore.domain.ReasonCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * US-104.1: a version lower than the stored version is rejected (A-02, A-12). The same or a higher version
 * is allowed (US-102, US-103), including for an expired trade (A-09). Skipped when there is no stored trade
 * or the incoming version is itself invalid (PositiveVersionValidator reports that).
 */
@Component
@Order(400)
public class LowerVersionValidator implements TradeValidator {

    @Override
    public List<Violation> validate(ValidationContext context) {
        Integer version = context.submission().version();
        if (version == null || version < 1 || context.stored().isEmpty()) {
            return List.of();
        }
        int storedVersion = context.stored().get().version();
        if (version < storedVersion) {
            return List.of(new Violation(ReasonCode.LOWER_VERSION, "version",
                    "version " + version + " is lower than stored version " + storedVersion));
        }
        return List.of();
    }
}
