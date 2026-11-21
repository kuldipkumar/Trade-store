package com.tradestore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** US-202: the scheduled expiry run. */
class ExpirySchedulerTest {

    private final ExpiryService expiry = mock(ExpiryService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ManualTaskScheduler taskScheduler = new ManualTaskScheduler();
    private final ExpiryScheduler scheduler =
            new ExpiryScheduler(expiry, registry, taskScheduler, true, Duration.ofMinutes(1), 3);

    @Test
    @Tag("US-202.1")
    void should_runTheExpiry_when_theScheduleFires() {
        // Act
        scheduler.runScheduled();

        // Assert
        verify(expiry).expireMaturedTrades();
    }

    @Test
    @Tag("US-202.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_countAndLogTheFailureWithoutThrowing_when_theRunFails(CapturedOutput output) {
        // Arrange
        when(expiry.expireMaturedTrades()).thenThrow(new DataAccessResourceFailureException("PostgreSQL down"));

        // Act + Assert: the next run (or a restart) catches up, so the scheduler keeps going
        assertThatCode(scheduler::runScheduled).doesNotThrowAnyException();
        var failures = registry.find("trade.job.failures").tag("job", "expiry").counter();
        assertThat(failures == null ? 0 : failures.count()).isEqualTo(1.0);
        assertThat(output).contains("ERROR").contains("Expiry run (cron) failed");
    }

    @Test
    @Tag("US-202.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logTheUnderlyingCause_when_theRunFails(CapturedOutput output) {
        // Arrange: the database driver's reason is wrapped by Spring's exception
        when(expiry.expireMaturedTrades()).thenThrow(
                new DataAccessResourceFailureException("Could not get connection", new SQLException("connection refused")));

        // Act
        scheduler.runScheduled();

        // Assert: the cause chain is logged, not only the outer message
        assertThat(output).contains("connection refused");
    }

    @Test
    @Tag("US-604.3")
    @ExtendWith(OutputCaptureExtension.class)
    void should_logHowManyTradesExpired_when_theRunSucceeds(CapturedOutput output) {
        // Arrange
        when(expiry.expireMaturedTrades()).thenReturn(3);

        // Act
        scheduler.runScheduled();

        // Assert (05-seq-expiry: log INFO "expired {count} trades")
        assertThat(output).contains("Expiry run (cron) expired 3 trades");
    }

    @Test
    @Tag("US-203.1")
    void should_runTheExpiryOffTheStartupThread_when_theApplicationIsReady() {
        // Act (catch up after downtime, A-51)
        scheduler.onApplicationReady();

        // Assert: startup is not held up (readiness is reported only after ready-event listeners return)
        verify(expiry, never()).expireMaturedTrades();
        assertThat(taskScheduler.scheduled).hasSize(1);
        taskScheduler.runNext();
        verify(expiry).expireMaturedTrades();
    }

    @Test
    @Tag("US-203.1")
    void should_notRunTheExpiryAtStartup_when_runAtStartupIsDisabled() {
        // Arrange
        ExpiryScheduler disabled = new ExpiryScheduler(expiry, registry, taskScheduler, false, Duration.ofMinutes(1), 3);

        // Act
        disabled.onApplicationReady();

        // Assert
        verify(expiry, never()).expireMaturedTrades();
    }

    @Test
    @Tag("US-203.1")
    void should_retryTheStartupRunAfterTheInterval_when_itFails() {
        // Arrange: the first attempt hits a transient database error, the retry succeeds
        when(expiry.expireMaturedTrades())
                .thenThrow(new DataAccessResourceFailureException("PostgreSQL not ready"))
                .thenReturn(2);
        scheduler.onApplicationReady();

        // Act: first attempt
        taskScheduler.runNext();

        // Assert: a retry is scheduled one interval later, not left to tomorrow's cron
        assertThat(taskScheduler.scheduled).singleElement()
                .satisfies(next -> assertThat(next.at()).isEqualTo(ManualTaskScheduler.NOW.plus(Duration.ofMinutes(1))));
        taskScheduler.runNext();
        assertThat(taskScheduler.scheduled).isEmpty();
        verify(expiry, times(2)).expireMaturedTrades();
    }

    @Test
    @Tag("US-203.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_stopRetryingAndSaySo_when_everyStartupAttemptFails(CapturedOutput output) {
        // Arrange: max 3 attempts; the database stays down
        when(expiry.expireMaturedTrades()).thenThrow(new DataAccessResourceFailureException("PostgreSQL down"));
        scheduler.onApplicationReady();

        // Act
        taskScheduler.runNext();
        taskScheduler.runNext();
        taskScheduler.runNext();

        // Assert: no fourth attempt; the cron run remains the fallback
        assertThat(taskScheduler.scheduled).isEmpty();
        assertThat(output).contains("Startup expiry catch-up gave up after 3 attempts");
    }

    @Test
    @Tag("US-604.3")
    @ExtendWith(OutputCaptureExtension.class)
    void should_tagTheLogLineAsStartup_when_theStartupRunSucceeds(CapturedOutput output) {
        // Arrange
        when(expiry.expireMaturedTrades()).thenReturn(4);
        scheduler.onApplicationReady();

        // Act
        taskScheduler.runNext();

        // Assert: startup and cron runs can be told apart in the log
        assertThat(output).contains("Expiry run (startup) expired 4 trades");
    }
}
