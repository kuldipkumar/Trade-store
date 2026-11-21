package com.tradestore.it;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Test-only clock for integration tests: the system clock unless a test pins it to a date. Tests that pin it
 * must call {@link #reset()} afterwards (the Spring context, and so this bean, is shared by all ITs).
 */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Instant pinned;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    /** Pins "now" to noon of the given date in this clock's zone. */
    public void setToday(LocalDate date) {
        this.pinned = date.atTime(12, 0).atZone(zone).toInstant();
    }

    public void reset() {
        this.pinned = null;
    }

    @Override
    public Instant instant() {
        Instant p = pinned;
        return p != null ? p : Instant.now();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock has a fixed zone");
    }
}
