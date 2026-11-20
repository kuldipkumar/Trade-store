package com.tradestore.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class MaturityDatesTest {

    @Test
    @Tag("US-105.3")
    void should_parseDate_when_valueIsStrictIso() {
        // Act + Assert
        assertThat(MaturityDates.parse("2027-05-20")).contains(LocalDate.of(2027, 5, 20));
    }

    @Test
    @Tag("US-105.3")
    void should_returnEmpty_when_dayDoesNotExistInMonth() {
        // Act + Assert (a lenient parser would roll 2027-02-30 over to March)
        assertThat(MaturityDates.parse("2027-02-30")).isEmpty();
    }

    @Test
    @Tag("US-105.3")
    void should_returnEmpty_when_valueIsNull() {
        // Act + Assert
        assertThat(MaturityDates.parse(null)).isEmpty();
    }
}
