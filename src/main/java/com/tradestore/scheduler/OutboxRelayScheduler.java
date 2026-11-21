package com.tradestore.scheduler;

import com.tradestore.config.TradeStoreProperties;
import com.tradestore.persistence.OutboxRepository;
import com.tradestore.service.OutboxRelayService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox (ADR-005). A failed batch stays in the outbox and is retried on the next poll. */
@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayService relay;
    private final Counter failedPolls;

    @Autowired
    public OutboxRelayScheduler(OutboxRelayService relay, OutboxRepository outbox, MeterRegistry registry,
                                TradeStoreProperties properties) {
        this(relay, outbox, registry, properties.outbox().alertAfterFailedPolls());
    }

    OutboxRelayScheduler(OutboxRelayService relay, OutboxRepository outbox, MeterRegistry registry,
                         int alertAfterFailedPolls) {
        this.relay = relay;
        this.failedPolls = registry.counter("trade.job.failures", "job", "outbox-relay");
    }

    @Scheduled(fixedDelayString = "${trade-store.outbox.poll-interval:1s}")
    public void poll() {
        try {
            relay.relayBatch();
        } catch (RuntimeException e) {
            failedPolls.increment();
            log.warn("Outbox relay failed; events stay in the outbox and are retried on the next poll: {}", e.toString());
        }
    }
}
