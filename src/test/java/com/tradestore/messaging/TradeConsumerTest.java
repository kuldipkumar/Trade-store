package com.tradestore.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tradestore.api.TradeJsonParser;
import com.tradestore.domain.Channel;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.service.TradeIngestionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** US-301: the stream adapter uses the same parsing and ingestion use case as REST (A-21). */
class TradeConsumerTest {

    private final TradeIngestionService ingestion = mock(TradeIngestionService.class);
    private final TradeConsumer consumer = new TradeConsumer(ingestion, new TradeJsonParser(JsonMapper.builder().build()));

    @Test
    @Tag("US-301.1")
    void should_ingestTheTradeAsAStreamSubmission_when_aMessageArrives() {
        // Arrange
        String message = "{\"tradeId\":\"T1\",\"version\":1,\"counterPartyId\":\"CP-1\",\"bookId\":\"B1\",\"maturityDate\":\"2027-05-20\"}";

        // Act
        consumer.onMessage(message);

        // Assert: channel STREAM, and the raw message kept for the audit trail
        verify(ingestion).ingest(new TradeSubmission("T1", 1, "CP-1", "B1", "2027-05-20", Channel.STREAM, message));
    }
}
