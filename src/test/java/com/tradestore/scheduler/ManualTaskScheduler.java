package com.tradestore.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/** Test double: records one-off tasks instead of running them, so a test controls when and whether they run. */
class ManualTaskScheduler implements TaskScheduler {

    record Scheduled(Runnable task, Instant at) {
    }

    static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");

    final List<Scheduled> scheduled = new ArrayList<>();

    @Override
    public Clock getClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        scheduled.add(new Scheduled(task, startTime));
        return null;
    }

    /** Runs the oldest pending task. */
    void runNext() {
        scheduled.removeFirst().task().run();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        throw new UnsupportedOperationException();
    }
}
