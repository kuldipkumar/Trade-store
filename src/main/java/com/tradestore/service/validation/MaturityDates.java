package com.tradestore.service.validation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/** Strict parsing of input maturity dates (A-04): exactly yyyy-MM-dd, and the date must exist. */
public final class MaturityDates {

    // 'uuuu' (not 'yyyy') is required for ResolverStyle.STRICT to work without an era field.
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private MaturityDates() {
    }

    public static Optional<LocalDate> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value, FORMAT));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
