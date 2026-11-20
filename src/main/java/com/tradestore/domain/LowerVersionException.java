package com.tradestore.domain;

import java.util.List;

/**
 * Raised when an incoming version is lower than the stored one (US-104.1, A-12). It may carry further
 * reasons too, for example MATURITY_IN_PAST (US-201.4).
 */
public class LowerVersionException extends TradeRejectedException {

    private final int incomingVersion;
    private final int storedVersion;

    LowerVersionException(String tradeId, List<Violation> violations, int incomingVersion, int storedVersion) {
        super(tradeId, violations,
                "Trade " + tradeId + " version " + incomingVersion + " is lower than stored version " + storedVersion);
        this.incomingVersion = incomingVersion;
        this.storedVersion = storedVersion;
    }

    public int incomingVersion() {
        return incomingVersion;
    }

    public int storedVersion() {
        return storedVersion;
    }
}
