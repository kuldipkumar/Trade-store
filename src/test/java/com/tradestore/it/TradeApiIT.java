package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/** US-401: submitting a trade over REST, against the running application and real databases. */
@IntegrationTest
class TradeApiIT {

    private final RestTestClient rest;
    private final Clock clock;

    private final String tradeId = "R-" + UUID.randomUUID();

    TradeApiIT(RestTestClient rest, Clock clock) {
        this.rest = rest;
        this.clock = clock;
    }

    private String json(int version, String maturity) {
        return """
                {"tradeId":"%s","version":%d,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"%s"}
                """.formatted(tradeId, version, maturity);
    }

    private EntityExchangeResult<JsonNode> post(String body) {
        return rest.post().uri("/trades").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
                .expectBody(JsonNode.class).returnResult();
    }

    @Test
    @Tag("US-401.1")
    void should_return201WithTheStoredTrade_when_aNewTradeIsSubmitted() {
        // Act
        EntityExchangeResult<JsonNode> response = post(json(1, "2099-05-20"));

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.CREATED);
        JsonNode trade = response.getResponseBody();
        assertThat(trade.path("tradeId").asString()).isEqualTo(tradeId);
        assertThat(trade.path("version").asInt()).isEqualTo(1);
        assertThat(trade.path("counterPartyId").asString()).isEqualTo("CP-1");
        assertThat(trade.path("bookId").asString()).isEqualTo("B1");
        assertThat(trade.path("maturityDate").asString()).isEqualTo("2099-05-20");
        assertThat(trade.path("createdDate").asString()).isEqualTo(LocalDate.now(clock).toString());
        assertThat(trade.path("expired").asBoolean()).isFalse();
    }
}
