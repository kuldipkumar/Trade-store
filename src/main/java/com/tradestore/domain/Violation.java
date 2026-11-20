package com.tradestore.domain;

/**
 * One broken rule.
 *
 * @param reason  the rule that was broken
 * @param field   the input field concerned
 * @param message a human-readable explanation, safe to return to clients
 */
public record Violation(ReasonCode reason, String field, String message) {

    public static Violation invalidField(String field, String message) {
        return new Violation(ReasonCode.INVALID_FIELD, field, message);
    }
}
