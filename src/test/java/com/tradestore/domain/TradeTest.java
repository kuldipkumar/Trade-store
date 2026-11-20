package com.tradestore.domain;

import static com.tradestore.TestTrades.TODAY;
import static com.tradestore.TestTrades.validSubmission;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-101.1 / US-101.2: the store, not the sender, sets createdDate and expired (A-05). */
class TradeTest {

    @Test
    @Tag("US-101.1")
    @Tag("US-101.2")
    void should_setCreatedDateToTodayAndNotExpired_when_newVersionIsAccepted() {
        // Act
        Trade trade = Trade.newVersion(validSubmission(), LocalDate.of(2027, 5, 20), TODAY);

        // Assert
        assertThat(trade).isEqualTo(new Trade("T1", 1, "CP-1", "B1", LocalDate.of(2027, 5, 20), TODAY, false));
    }
}
