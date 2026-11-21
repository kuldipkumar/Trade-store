package com.tradestore.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tradestore.service.ExpiryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-202: the scheduled expiry run. */
class ExpirySchedulerTest {

    private final ExpiryService expiry = mock(ExpiryService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ExpiryScheduler scheduler = new ExpiryScheduler(expiry, registry);

    @Test
    @Tag("US-202.1")
    void should_runTheExpiry_when_theScheduleFires() {
        // Act
        scheduler.runScheduled();

        // Assert
        verify(expiry).expireMaturedTrades();
    }
}
