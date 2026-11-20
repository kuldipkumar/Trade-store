package com.tradestore;

import java.time.LocalDate;
import java.util.Optional;

import com.tradestore.domain.Channel;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.service.validation.ValidationContext;

/** Test data builders. "Today" is fixed at 2026-11-19, as in the backlog's acceptance criteria. */
public final class TestTrades {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private TestTrades() {
    }

    /** A valid submission: T1 v1, CP-1, B1, maturing 2027-05-20, over REST. */
    public static TradeSubmission validSubmission() {
        return new TradeSubmission("T1", 1, "CP-1", "B1", "2027-05-20", Channel.REST, "{}");
    }

    public static TradeSubmission withTradeId(String tradeId) {
        TradeSubmission s = validSubmission();
        return new TradeSubmission(tradeId, s.version(), s.counterPartyId(), s.bookId(), s.maturityDate(), s.channel(), s.rawPayload());
    }

    public static TradeSubmission withVersion(Integer version) {
        TradeSubmission s = validSubmission();
        return new TradeSubmission(s.tradeId(), version, s.counterPartyId(), s.bookId(), s.maturityDate(), s.channel(), s.rawPayload());
    }

    public static TradeSubmission withCounterPartyId(String counterPartyId) {
        TradeSubmission s = validSubmission();
        return new TradeSubmission(s.tradeId(), s.version(), counterPartyId, s.bookId(), s.maturityDate(), s.channel(), s.rawPayload());
    }

    public static TradeSubmission withBookId(String bookId) {
        TradeSubmission s = validSubmission();
        return new TradeSubmission(s.tradeId(), s.version(), s.counterPartyId(), bookId, s.maturityDate(), s.channel(), s.rawPayload());
    }

    public static TradeSubmission withMaturityDate(String maturityDate) {
        TradeSubmission s = validSubmission();
        return new TradeSubmission(s.tradeId(), s.version(), s.counterPartyId(), s.bookId(), maturityDate, s.channel(), s.rawPayload());
    }

    public static ValidationContext contextFor(TradeSubmission submission) {
        return new ValidationContext(submission, Optional.empty(), TODAY);
    }

    public static ValidationContext contextFor(TradeSubmission submission, StoredTrade stored) {
        return new ValidationContext(submission, Optional.of(stored), TODAY);
    }
}
