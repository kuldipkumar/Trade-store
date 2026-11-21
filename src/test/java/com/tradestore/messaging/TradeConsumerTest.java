package com.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tradestore.service.parsing.TradeJsonParser;
import java.util.List;
import java.util.Optional;

import com.tradestore.domain.Channel;
import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.TradeRejectedException;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.domain.Violation;
import com.tradestore.service.parsing.MalformedTradeException;
import com.tradestore.service.TradeIngestionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** US-301: the stream adapter uses the same parsing and ingestion use case as REST (A-21). */
class TradeConsumerTest {

    private final TradeIngestionService ingestion = mock(TradeIngestionService.class);
    private final TradeConsumer consumer = new TradeConsumer(ingestion, new TradeJsonParser(JsonMapper.builder().build()), 120);

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

    @Test
    @Tag("US-304.1")
    void should_notRethrow_when_theTradeIsRejected() {
        // Arrange: the rejection is already audited (committed before it is thrown)
        when(ingestion.ingest(any())).thenThrow(TradeRejectedException.of(
                new TradeSubmission("T2", 1, "CP-1", "B1", "2027-05-20", Channel.STREAM, "{}"),
                Optional.of(new StoredTrade("T2", 2, false)), List.of(Violation.lowerVersion(1, 2))));

        // Act + Assert: final, so not retried or dead-lettered; the offset is committed (A-26)
        assertThatCode(() -> consumer.onMessage("{\"tradeId\":\"T2\",\"version\":1}")).doesNotThrowAnyException();
    }

    @Test
    @Tag("US-302.1")
    void should_treatAnEmptyMessageAsMalformed_when_theValueIsNull() {
        // Act + Assert: a tombstone or empty value is not a trade
        assertThatThrownBy(() -> consumer.onMessage(null)).isInstanceOf(MalformedTradeException.class);
    }

    @Test
    @Tag("US-607.1")
    void should_treatTheMessageAsMalformedWithoutParsingIt_when_itIsLargerThanTheLimit() {
        // Arrange: a valid trade padded past the 120-byte limit
        String message = "{\"tradeId\":\"T1\",\"version\":1,\"counterPartyId\":\"CP-1\",\"bookId\":\"B1\","
                + "\"maturityDate\":\"2027-05-20\",\"note\":\"" + "x".repeat(40) + "\"}";

        // Act + Assert: a poison pill (dead-lettered, audited with a capped payload), never ingested
        assertThatThrownBy(() -> consumer.onMessage(message)).isInstanceOf(MalformedTradeException.class)
                .hasMessageContaining("larger than 120 bytes");
        verifyNoInteractions(ingestion);
    }
}
