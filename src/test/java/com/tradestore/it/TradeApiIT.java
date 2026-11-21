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

    @Test
    @Tag("US-401.2")
    void should_return200WithTheStoredTrade_when_aStoredTradeIsReplaced() {
        // Arrange
        post(json(1, "2099-05-20"));

        // Act: the same version, then a higher one
        EntityExchangeResult<JsonNode> same = post(json(1, "2099-05-20"));
        EntityExchangeResult<JsonNode> higher = post(json(2, "2099-06-20"));

        // Assert
        assertThat(same.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(higher.getStatus()).isEqualTo(HttpStatus.OK);
        assertThat(higher.getResponseBody().path("version").asInt()).isEqualTo(2);
        assertThat(higher.getResponseBody().path("maturityDate").asString()).isEqualTo("2099-06-20");
    }

    @Test
    @Tag("US-401.3")
    void should_return409WithTheStandardErrorShape_when_aLowerVersionIsSubmitted() {
        // Arrange
        post(json(2, "2099-05-20"));

        // Act
        EntityExchangeResult<JsonNode> response = post(json(1, "2099-05-20"));

        // Assert: {timestamp, status, error, reasons[], path}
        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode error = response.getResponseBody();
        assertThat(error.path("timestamp").asString()).isNotBlank();
        assertThat(error.path("status").asInt()).isEqualTo(409);
        assertThat(error.path("error").asString()).isEqualTo("Conflict");
        assertThat(error.path("path").asString()).isEqualTo("/trades");
        assertThat(error.path("reasons")).hasSize(1);
        assertThat(error.path("reasons").get(0).path("reason").asString()).isEqualTo("LOWER_VERSION");
        assertThat(error.path("reasons").get(0).path("field").asString()).isEqualTo("version");
        assertThat(error.path("reasons").get(0).path("message").asString()).contains("version 1").contains("stored version 2");
    }

    @Test
    @Tag("US-401.4")
    void should_return422_when_theMaturityDateIsInThePast() {
        // Act: yesterday, relative to the application's own clock
        EntityExchangeResult<JsonNode> response = post(json(1, LocalDate.now(clock).minusDays(1).toString()));

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getResponseBody().path("reasons").get(0).path("reason").asString()).isEqualTo("MATURITY_IN_PAST");
    }

    @Test
    @Tag("US-401.5")
    void should_return400ListingTheFieldErrors_when_fieldsAreInvalid() {
        // Act: blank bookId and version 0
        EntityExchangeResult<JsonNode> response = post("""
                {"tradeId":"%s","version":0,"counterPartyId":"CP-1","bookId":" ","maturityDate":"2099-05-20"}
                """.formatted(tradeId));

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getResponseBody().path("reasons").findValuesAsString("field")).containsExactly("bookId", "version");
    }

    @Test
    @Tag("US-401.5")
    void should_return400WithTheStandardErrorShape_when_theBodyIsNotValidJson() {
        // Act
        EntityExchangeResult<JsonNode> response = post("{\"tradeId\": \"T1\", ");

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getResponseBody().path("status").asInt()).isEqualTo(400);
        assertThat(response.getResponseBody().path("reasons").get(0).path("reason").asString()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    @Tag("US-401.5")
    void should_return400NotConflict_when_aLowerVersionAlsoHasInvalidFields() {
        // Arrange (decision D6: 400 > 409 > 422, every reason still listed)
        post(json(2, "2099-05-20"));

        // Act: lower version and a blank bookId
        EntityExchangeResult<JsonNode> response = post("""
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"","maturityDate":"2099-05-20"}
                """.formatted(tradeId));

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getResponseBody().path("reasons").findValuesAsString("reason"))
                .containsExactly("INVALID_FIELD", "LOWER_VERSION");
    }

    @Test
    @Tag("US-401.6")
    void should_return413_when_theBodyIsLargerThan1MB() {
        // Arrange: a valid trade padded with an unknown field to about 1.1 MB
        String body = """
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20","pad":"%s"}
                """.formatted(tradeId, "x".repeat(1_150_000));

        // Act
        EntityExchangeResult<JsonNode> response = post(body);

        // Assert (A-47: input capped at 1 MB)
        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getResponseBody().path("status").asInt()).isEqualTo(413);
        assertThat(response.getResponseBody().path("reasons").get(0).path("reason").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    @Tag("US-101.2")
    void should_ignoreCreatedDateAndExpiredSentByTheCaller_when_aTradeIsSubmitted() {
        // Act: the sender tries to set the store-owned fields
        EntityExchangeResult<JsonNode> response = post("""
                {"tradeId":"%s","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2099-05-20",
                 "createdDate":"2015-03-14","expired":"Y"}
                """.formatted(tradeId));

        // Assert (A-05: the store sets createdDate and expired)
        assertThat(response.getStatus()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getResponseBody().path("createdDate").asString()).isEqualTo(LocalDate.now(clock).toString());
        assertThat(response.getResponseBody().path("expired").asBoolean()).isFalse();
    }
}
