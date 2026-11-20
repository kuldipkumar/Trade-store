package com.tradestore.scheduler;

import com.tradestore.service.OutboxRelayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox (ADR-005). A failed batch stays in the outbox and is retried on the next poll. */
@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelayService relay;

    public OutboxRelayScheduler(OutboxRelayService relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${trade-store.outbox.poll-interval:1s}")
    public void poll() {
        try {
            relay.relayBatch();
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed; events stay in the outbox and are retried on the next poll: {}", e.toString());
        }
    }
}
