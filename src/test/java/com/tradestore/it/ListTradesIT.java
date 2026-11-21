package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import com.tradestore.domain.Trade;
import com.tradestore.persistence.TradeRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;

/**
 * US-403: the paged, filterable trade list. ITs share one database, so totals are checked relative to a
 * baseline taken before this test stores its own trades.
 */
@IntegrationTest
class ListTradesIT {

    private final RestTestClient rest;
    private final TradeRepository trades;
    private final JdbcClient jdbc;

    private final String prefix = "L-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    ListTradesIT(RestTestClient rest, TradeRepository trades, JdbcClient jdbc) {
        this.rest = rest;
        this.trades = trades;
        this.jdbc = jdbc;
    }

    private void store(int count) {
        for (int i = 0; i < count; i++) {
            trades.upsertIfNotOlder(new Trade(prefix + i, 1, "CP-1", "B1", LocalDate.of(2099, 1, 1), LocalDate.of(2026, 11, 21), false));
        }
    }

    private EntityExchangeResult<JsonNode> list(String query) {
        return rest.get().uri("/trades" + query).exchange().expectBody(JsonNode.class).returnResult();
    }

    @Test
    @Tag("US-403.1")
    void should_returnOnePageWithPagingMetadata_when_moreTradesThanThePageSizeAreStored() {
        // Arrange: 25 more trades than before
        long before = list("?page=0&size=1").getResponseBody().path("totalElements").asLong();
        store(25);

        // Act
        EntityExchangeResult<JsonNode> response = list("?page=0&size=10");

        // Assert
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        JsonNode page = response.getResponseBody();
        assertThat(page.path("content")).hasSize(10);
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(10);
        assertThat(page.path("totalElements").asLong()).isEqualTo(before + 25);
        assertThat(page.path("totalPages").asLong()).isEqualTo((before + 25 + 9) / 10);
    }

    @Test
    @Tag("US-403.2")
    void should_returnOnlyExpiredTrades_when_filteringOnExpired() {
        // Arrange: three live trades, one of them then marked expired
        store(3);
        jdbc.sql("UPDATE trades SET expired = true WHERE trade_id = ?").param(prefix + 1).update();

        // Act
        EntityExchangeResult<JsonNode> response = list("?expired=true&page=0&size=100");

        // Assert: every returned trade is expired, and ours is among them; our live ones are not
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK);
        JsonNode content = response.getResponseBody().path("content");
        assertThat(content.findValues("expired")).allMatch(JsonNode::asBoolean);
        assertThat(content.findValuesAsString("tradeId")).contains(prefix + 1).doesNotContain(prefix + 0, prefix + 2);
    }
}
