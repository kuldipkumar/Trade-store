package com.tradestore.service.validation;

import java.util.List;

import com.tradestore.domain.Violation;

/**
 * One business rule (AGENTS.md section 4: one class per rule). Implementations return every violation
 * they find, or an empty list, and never throw. They skip checks whose inputs are themselves invalid,
 * so one bad field does not produce a misleading second reason.
 */
public interface TradeValidator {

    List<Violation> validate(ValidationContext context);
}
