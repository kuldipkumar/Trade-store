package com.tradestore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.tradestore.config.SchedulingConfig;
import com.tradestore.config.TradeStoreProperties;
import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;

/** US-202.4: the expiry job's schedule comes from configuration, in the configured zone (A-18, A-50, US-205). */
class ExpiryScheduleConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, ExpiryScheduler.class)
            .withBean(ExpiryService.class, () -> mock(ExpiryService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(TradeStoreProperties.class, () -> new TradeStoreProperties(null, null, null, null));

    private static List<CronTask> cronTasks(ApplicationContext context) {
        return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .map(CronTask.class::cast)
                .toList();
    }

    @Test
    @Tag("US-202.4")
    void should_runJustAfterLocalMidnight_when_noScheduleIsConfigured() {
        runner.run(context -> assertThat(cronTasks(context)).extracting(CronTask::getExpression)
                .containsExactly("0 5 0 * * *"));
    }

    @Test
    @Tag("US-202.4")
    void should_useTheConfiguredCron_when_aScheduleIsConfigured() {
        runner.withPropertyValues("trade-store.expiry.cron=0 0 1 * * *")
                .run(context -> assertThat(cronTasks(context)).extracting(CronTask::getExpression)
                        .containsExactly("0 0 1 * * *"));
    }

    @Test
    @Tag("US-202.4")
    void should_fireAtMidnightInTheConfiguredZone_when_aTimeZoneIsConfigured() {
        runner.withPropertyValues("trade-store.time-zone=Asia/Kolkata").run(context -> {
            // Arrange: noon UTC on 2026-11-18
            SimpleTriggerContext from = new SimpleTriggerContext(Clock.fixed(Instant.parse("2026-11-18T12:00:00Z"), ZoneOffset.UTC));

            // Act
            Instant next = cronTasks(context).getFirst().getTrigger().nextExecution(from);

            // Assert: 00:05 on 19 Nov in Kolkata (UTC+05:30) is 18:35 UTC on 18 Nov
            assertThat(next).isEqualTo(Instant.parse("2026-11-18T18:35:00Z"));
        });
    }
}
