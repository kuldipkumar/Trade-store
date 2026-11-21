package com.tradestore.api;

import java.time.Instant;
import java.util.List;

import com.tradestore.domain.Violation;

/** The one error shape for every API error (docs/05-lld.md section 6.1): {timestamp, status, error, reasons[], path}. */
public record ApiErrorResponse(Instant timestamp, int status, String error, List<Reason> reasons, String path) {

    /** One reason: the rule broken, the field concerned, and a message safe to show to clients. */
    public record Reason(String reason, String field, String message) {

        static Reason from(Violation violation) {
            return new Reason(violation.reason().name(), violation.field(), violation.message());
        }
    }
}
