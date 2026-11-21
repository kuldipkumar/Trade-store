package com.tradestore.messaging;

import com.tradestore.service.parsing.TradeJsonParser;
import com.tradestore.domain.Channel;
import com.tradestore.service.TradeIngestionService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The stream channel (US-301). Messages are read as text, parsed strictly by the same {@link TradeJsonParser}
 * as REST (so the audit keeps the raw message), and ingested by the same use case (A-21). Offsets are committed
 * by the listener container only after this method returns (A-43).
 */
@Component
public class TradeConsumer {

    private final TradeIngestionService ingestion;
    private final TradeJsonParser parser;

    public TradeConsumer(TradeIngestionService ingestion, TradeJsonParser parser) {
        this.ingestion = ingestion;
        this.parser = parser;
    }

    @KafkaListener(topics = "${trade-store.kafka.topic:trades.inbound}", groupId = "${spring.kafka.consumer.group-id:trade-store}")
    public void onMessage(String message) {
        ingestion.ingest(parser.parse(message, Channel.STREAM));
    }
}
