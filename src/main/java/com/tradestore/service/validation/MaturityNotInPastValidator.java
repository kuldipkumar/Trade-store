package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Violation;

/** US-201: maturity date must not be before today (work in progress: fake it, then triangulate). */
public class MaturityNotInPastValidator implements TradeValidator {

    @Override
    public List<Violation> validate(ValidationContext context) {
        return List.of(new Violation(ReasonCode.MATURITY_IN_PAST, "maturityDate",
                "maturityDate " + context.submission().maturityDate() + " is before today " + context.today()));
    }
}
