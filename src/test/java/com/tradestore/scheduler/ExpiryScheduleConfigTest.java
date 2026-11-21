package com.tradestore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.SimpleTriggerContext;

/** US-202.4: the expiry job's schedule comes from configuration, in the configured zone (A-18, A-50, US-205). */
class ExpiryScheduleConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, BindProperties.class, ExpiryScheduler.class)
            .withBean(ExpiryService.class, () -> mock(ExpiryService.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(TaskScheduler.class, ThreadPoolTaskScheduler::new);

    /** Binds trade-store.* exactly as the application does. */
    @EnableConfigurationProperties(TradeStoreProperties.class)
    static class BindProperties {
    }

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

    private static void publishApplicationReady(ApplicationContext context) {
        ConfigurableApplicationContext configurable = (ConfigurableApplicationContext) context;
        configurable.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], configurable, Duration.ZERO));
    }

    @Test
    @Tag("US-203.1")
    void should_runTheExpiry_when_theApplicationReadyEventIsPublished() {
        runner.withPropertyValues("trade-store.expiry.cron=-").run(context -> {
            // Act: the service has started (the cron is disabled so only the startup run can call it)
            publishApplicationReady(context);

            // Assert: the run happens on the task scheduler, shortly after
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(context.getBean(ExpiryService.class)).expireMaturedTrades());
        });
    }

    @Test
    @Tag("US-203.1")
    void should_notRunAtStartup_when_runAtStartupIsSetToFalse() {
        runner.withPropertyValues("trade-store.expiry.cron=-", "trade-store.expiry.run-at-startup=false").run(context -> {
            // Act
            publishApplicationReady(context);

            // Assert: bound from the real property, nothing is scheduled or run
            await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> verify(context.getBean(ExpiryService.class), never()).expireMaturedTrades());
        });
    }
}
