package com.tradestore.service.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tradestore.domain.Channel;
import com.tradestore.domain.TradeSubmission;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strict trade JSON (decisions D2/D3): fields must have the right JSON types; values themselves are checked
 * by the validators. Unknown fields are ignored (US-607.2). The raw text is kept for the audit trail.
 */
class TradeJsonParserTest {

    private final TradeJsonParser parser = new TradeJsonParser(JsonMapper.builder().build());

    @Test
    @Tag("US-401.1")
    void should_keepRawValuesAndTheOriginalText_when_theJsonIsWellFormed() {
        // Arrange
        String body = "{\"tradeId\":\"T1\",\"version\":2,\"counterPartyId\":\"CP-1\",\"bookId\":\"B1\",\"maturityDate\":\"2027-05-20\",\"x\":1}";

        // Act
        TradeSubmission s = parser.parse(body, Channel.REST);

        // Assert
        assertThat(s).isEqualTo(new TradeSubmission("T1", 2, "CP-1", "B1", "2027-05-20", Channel.REST, body));
    }

    @Test
    @Tag("US-105.1")
    void should_leaveMissingOrNullFieldsNull_when_theyAreAbsent() {
        // Act: the validators turn these into INVALID_FIELD, which is audited
        TradeSubmission s = parser.parse("{\"tradeId\":null}", Channel.REST);

        // Assert
        assertThat(s.tradeId()).isNull();
        assertThat(s.version()).isNull();
        assertThat(s.maturityDate()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"tradeId\":\"T1\",\"version\":1.9}",
            "{\"tradeId\":\"T1\",\"version\":\"1\"}",
            "{\"tradeId\":\"T1\",\"version\":99999999999}",
            "{\"tradeId\":123}",
            "{\"tradeId\":\"T1\",\"bookId\":true}",
            "{\"tradeId\":\"T1\",\"maturityDate\":20270520}",
            "[\"T1\"]",
            "{\"tradeId\": ",
            "not json"})
    @Tag("US-401.5")
    void should_reportAMalformedTrade_when_theJsonOrAFieldTypeIsWrong(String body) {
        // Act + Assert
        assertThatThrownBy(() -> parser.parse(body, Channel.REST)).isInstanceOf(MalformedTradeException.class);
    }

    @Test
    @Tag("US-607.2")
    void should_ignoreUnknownFields_when_aValidTradeHasExtras() {
        // Arrange
        String body = "{\"tradeId\":\"T1\",\"version\":1,\"counterPartyId\":\"CP-1\",\"bookId\":\"B1\","
                + "\"maturityDate\":\"2027-05-20\",\"desk\":\"FX\",\"tags\":[1,{\"a\":null}]}";

        // Act
        TradeSubmission parsed = parser.parse(body, Channel.STREAM);

        // Assert: processed as usual; the raw text (extras included) is kept for the audit
        assertThat(parsed).isEqualTo(new TradeSubmission("T1", 1, "CP-1", "B1", "2027-05-20", Channel.STREAM, body));
    }
}
