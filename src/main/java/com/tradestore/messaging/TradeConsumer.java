package com.tradestore.messaging;

import com.tradestore.domain.Channel;
import com.tradestore.domain.TradeRejectedException;
import com.tradestore.service.TradeIngestionService;
import com.tradestore.service.parsing.MalformedTradeException;
import com.tradestore.service.parsing.TradeJsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tradestore.config.TradeStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * The stream channel (US-301). Messages are read as text, parsed strictly by the same {@link TradeJsonParser}
 * as REST (so the audit keeps the raw message), and ingested by the same use case (A-21). Offsets are committed
 * by the listener container only after this method returns (A-43).
 * <p>
 * A business rejection is final (US-304, A-26): it is already audited, so it is not rethrown, retried or
 * dead-lettered. Anything else propagates to the container's error handler (KafkaConfig).
 */
@Component
public class TradeConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeConsumer.class);

    private final TradeIngestionService ingestion;
    private final TradeJsonParser parser;
    private final int maxMessageBytes;

    @Autowired
    public TradeConsumer(TradeIngestionService ingestion, TradeJsonParser parser, TradeStoreProperties properties) {
        this(ingestion, parser, properties.kafka().maxMessageBytes());
    }

    TradeConsumer(TradeIngestionService ingestion, TradeJsonParser parser, int maxMessageBytes) {
        this.ingestion = ingestion;
        this.parser = parser;
        this.maxMessageBytes = maxMessageBytes;
    }

    @KafkaListener(topics = "${trade-store.kafka.topic:trades.inbound}", groupId = "${spring.kafka.consumer.group-id:trade-store}")
    public void onMessage(@Payload(required = false) String message) {
        if (message == null || message.isBlank()) {
            throw new MalformedTradeException("The message is empty");
        }
        try {
            ingestion.ingest(parser.parse(message, Channel.STREAM));
        } catch (TradeRejectedException rejected) {
            log.debug("Rejection is final on the stream (already audited): {}", rejected.getMessage());
        }
    }
}
