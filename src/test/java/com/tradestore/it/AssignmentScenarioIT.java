package com.tradestore.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

import com.tradestore.domain.Channel;
import com.tradestore.domain.LowerVersionException;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.Trade;
import com.tradestore.domain.TradeRejectedException;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.persistence.TradeRepository;
import com.tradestore.service.ExpiryService;
import com.tradestore.service.IngestionResult;
import com.tradestore.service.TradeIngestionService;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * US-107: the assignment's sample trades replayed through the real application (A-01: dates keep the shape of
 * the sample, relative to "today" = 2026-11-19). The steps build on each other, so they run in order and share
 * this instance; trade IDs carry a unique prefix so the audit counts are exact.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssignmentScenarioIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final Duration RELAY_TIMEOUT = Duration.ofSeconds(10);

    private final TradeIngestionService ingestion;
    private final ExpiryService expiry;
    private final TradeRepository trades;
    private final MongoTemplate mongo;
    private final MutableClock clock;

    private final String prefix = "S-" + UUID.randomUUID().toString().substring(0, 8) + "-";
    private final String t1 = prefix + "T1";
    private final String t2 = prefix + "T2";
    private final String t3 = prefix + "T3";

    AssignmentScenarioIT(TradeIngestionService ingestion, ExpiryService expiry, TradeRepository trades,
                         MongoTemplate mongo, MutableClock clock) {
        this.ingestion = ingestion;
        this.expiry = expiry;
        this.trades = trades;
        this.mongo = mongo;
        this.clock = clock;
    }

    @BeforeAll
    void pinToday() {
        clock.setToday(TODAY);
    }

    @AfterAll
    void resetClock() {
        clock.reset();
    }

    private static TradeSubmission trade(String id, int version, String cp, String book, String maturity) {
        return new TradeSubmission(id, version, cp, book, maturity, Channel.STREAM, "{}");
    }

    private long audit(Outcome outcome) {
        return mongo.getCollection("audit_events").countDocuments(new Document("tradeId", new Document("$regex", "^" + prefix))
                .append("outcome", outcome.name()));
    }

    @Test
    @Order(1)
    @Tag("US-107.1")
    void should_acceptT1_when_theStoreIsEmpty() {
        // Act: T1 v1, CP-1, B1, maturity 2027-05-20
        IngestionResult result = ingestion.ingest(trade(t1, 1, "CP-1", "B1", "2027-05-20"));

        // Assert: stored with createdDate = today and expired = N
        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(trades.findTrade(t1)).contains(new Trade(t1, 1, "CP-1", "B1", LocalDate.of(2027, 5, 20), TODAY, false));
    }

    @Test
    @Order(2)
    @Tag("US-107.2")
    void should_acceptT2v2_when_T1IsStored() {
        // Act: T2 v2, CP-2, B1, maturity 2027-05-20
        IngestionResult result = ingestion.ingest(trade(t2, 2, "CP-2", "B1", "2027-05-20"));

        // Assert
        assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(trades.findTrade(t2).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    @Order(3)
    @Tag("US-107.3")
    void should_rejectT2v1AsLowerVersion_when_T2v2IsStored() {
        // Act + Assert: T2 v1, CP-1, B1, maturity 2027-05-20
        assertThatThrownBy(() -> ingestion.ingest(trade(t2, 1, "CP-1", "B1", "2027-05-20")))
                .isInstanceOf(LowerVersionException.class);
        assertThat(trades.findTrade(t2).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    @Order(4)
    @Tag("US-107.4")
    void should_rejectT3AsMaturityInThePast_when_todayIs20261119() {
        // Act + Assert: T3 v3, CP-3, B2, maturity 2026-05-20
        assertThatThrownBy(() -> ingestion.ingest(trade(t3, 3, "CP-3", "B2", "2026-05-20")))
                .isInstanceOf(TradeRejectedException.class)
                .satisfies(e -> assertThat(((TradeRejectedException) e).reasons()).containsExactly(ReasonCode.MATURITY_IN_PAST));
        assertThat(trades.findTrade(t3)).isEmpty();
    }

    @Test
    @Order(5)
    @Tag("US-107.5")
    void should_expireT1AndT2AndAuditEveryOutcome_when_theClockMovesPastMaturity() {
        // Act: the clock moves to 2027-05-21 and the expiry job runs
        clock.setToday(LocalDate.of(2027, 5, 21));
        expiry.expireMaturedTrades();

        // Assert: T1 and T2 expired; the audit trail holds 2 ACCEPTED, 2 REJECTED and 2 EXPIRED entries
        assertThat(trades.findById(t1).orElseThrow().expired()).isTrue();
        assertThat(trades.findById(t2).orElseThrow().expired()).isTrue();
        await().atMost(RELAY_TIMEOUT).untilAsserted(() -> {
            assertThat(audit(Outcome.ACCEPTED)).isEqualTo(2);
            assertThat(audit(Outcome.REJECTED)).isEqualTo(2);
            assertThat(audit(Outcome.EXPIRED)).isEqualTo(2);
        });
    }
}
