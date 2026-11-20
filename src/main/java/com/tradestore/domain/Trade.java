package com.tradestore.domain;

import java.time.LocalDate;

/**
 * A stored, valid trade: the latest version held in PostgreSQL. Immutable.
 *
 * @param createdDate the local date this version was received (A-05, A-08)
 * @param expired     true once maturityDate has passed (A-17)
 */
public record Trade(
        String tradeId,
        int version,
        String counterPartyId,
        String bookId,
        LocalDate maturityDate,
        LocalDate createdDate,
        boolean expired) {

    /**
     * Builds the trade to store for a submission that passed validation. The store sets createdDate and
     * expired; anything the sender supplied for them is ignored (A-05, A-09).
     */
    public static Trade newVersion(TradeSubmission submission, LocalDate maturityDate, LocalDate today) {
        return new Trade(submission.tradeId(), submission.version(), submission.counterPartyId(),
                submission.bookId(), maturityDate, today, false);
    }
}
