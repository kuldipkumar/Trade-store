package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;

/**
 * US-201: the maturity date must not be before today (A-13: today itself is valid; A-14: applies to every
 * version). "Today" comes from the injected Clock via the ValidationContext (A-16). Skipped when the date
 * can't be parsed; MaturityDateFormatValidator reports that.
 */
public class MaturityNotInPastValidator implements TradeValidator {

    @Override
    public List<Violation> validate(ValidationContext context) {
        return MaturityDates.parse(context.submission().maturityDate())
                .filter(maturity -> maturity.isBefore(context.today()))
                .map(maturity -> List.of(new Violation(ReasonCode.MATURITY_IN_PAST, "maturityDate",
                        "maturityDate " + maturity + " is before today " + context.today())))
                .orElse(List.of());
    }
}
