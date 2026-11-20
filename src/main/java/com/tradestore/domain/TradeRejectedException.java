package com.tradestore.domain;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The single exception raised for a rejected trade, carrying every violated reason (US-104.4, A-15).
 * Build it with {@link #of}, which returns a {@link LowerVersionException} when LOWER_VERSION is among
 * the reasons, so callers can still catch the version rule specifically (US-104.1).
 */
public class TradeRejectedException extends RuntimeException {

    private final String tradeId;
    private final List<Violation> violations;

    protected TradeRejectedException(String tradeId, List<Violation> violations, String message) {
        super(message);
        this.tradeId = tradeId;
        this.violations = List.copyOf(violations);
    }

    public static TradeRejectedException of(TradeSubmission submission, Optional<StoredTrade> stored,
                                            List<Violation> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("A rejection needs at least one violation");
        }
        boolean lowerVersion = violations.stream().anyMatch(v -> v.reason() == ReasonCode.LOWER_VERSION);
        if (lowerVersion && stored.isPresent()) {
            return new LowerVersionException(submission.tradeId(), violations, submission.version(), stored.get().version());
        }
        return new TradeRejectedException(submission.tradeId(), violations, describe(submission.tradeId(), violations));
    }

    private static String describe(String tradeId, List<Violation> violations) {
        return "Trade " + tradeId + " rejected: " + violations.stream()
                .map(v -> v.reason() + " (" + v.field() + ")")
                .collect(Collectors.joining(", "));
    }

    public String tradeId() {
        return tradeId;
    }

    public List<Violation> violations() {
        return violations;
    }

    public List<ReasonCode> reasons() {
        return violations.stream().map(Violation::reason).toList();
    }
}
