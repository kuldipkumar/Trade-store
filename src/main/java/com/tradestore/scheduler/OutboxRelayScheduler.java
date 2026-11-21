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
    private final OutboxRepository outbox;
    private final int alertAfterFailedPolls;
    private int consecutiveFailures;

    @Autowired
    public OutboxRelayScheduler(OutboxRelayService relay, OutboxRepository outbox, MeterRegistry registry,
                                TradeStoreProperties properties) {
        this(relay, outbox, registry, properties.outbox().alertAfterFailedPolls());
    }

    OutboxRelayScheduler(OutboxRelayService relay, OutboxRepository outbox, MeterRegistry registry,
                         int alertAfterFailedPolls) {
        this.relay = relay;
        this.failedPolls = registry.counter("trade.job.failures", "job", "outbox-relay");
        this.outbox = outbox;
        this.alertAfterFailedPolls = alertAfterFailedPolls;
    }

    @Scheduled(fixedDelayString = "${trade-store.outbox.poll-interval:1s}")
    public void poll() {
        try {
            relay.relayBatch();
        } catch (RuntimeException e) {
            failedPolls.increment();
            consecutiveFailures++;
            if (consecutiveFailures == alertAfterFailedPolls) {
                log.error("Audit relay has failed for {} consecutive polls; outcomes are kept in the outbox and retried. "
                        + "Trades waiting for their audit entries: {}", consecutiveFailures, outbox.pendingTradeIds(20));
            }
            log.warn("Outbox relay failed; events stay in the outbox and are retried on the next poll: {}", e.toString());
        }
    }
}
