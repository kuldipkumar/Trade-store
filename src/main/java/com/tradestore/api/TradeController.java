package com.tradestore.api;

import java.util.List;

import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.service.IngestionResult;
import com.tradestore.service.TradeIngestionService;
import com.tradestore.service.TradeQueryService;
import com.tradestore.service.parsing.TradeJsonParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trades over REST: submission (US-401), lookup by ID (US-402), list (US-403) and version history (US-404). Uses the same ingestion use case as the stream (A-21). The body is read
 * as text, so the audit trail keeps exactly what the client sent, and parsed strictly by {@link TradeJsonParser}.
 */
@RestController
@RequestMapping("/trades")
public class TradeController {

    private final TradeIngestionService ingestion;
    private final TradeJsonParser parser;
    private final TradeQueryService queries;

    public TradeController(TradeIngestionService ingestion, TradeJsonParser parser, TradeQueryService queries) {
        this.ingestion = ingestion;
        this.parser = parser;
        this.queries = queries;
    }

    /** A page of trades sorted by tradeId, optionally only expired or only live ones (US-403). */
    @GetMapping
    public PageResponse<TradeResponse> list(@RequestParam(required = false) Boolean expired,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        PageParameters.validate(page, size);
        return PageResponse.from(queries.list(expired, page, size), TradeResponse::from);
    }

    /** Every accepted version, ordered by version then received time (US-404); 404 if none. */
    @GetMapping("/{tradeId}/history")
    public List<TradeVersionResponse> history(@PathVariable String tradeId) {
        return queries.history(tradeId).stream().map(TradeVersionResponse::from).toList();
    }

    /** The latest stored version (US-402); 404 if unknown. */
    @GetMapping("/{tradeId}")
    public TradeResponse get(@PathVariable String tradeId) {
        return TradeResponse.from(queries.get(tradeId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TradeResponse> submit(@RequestBody String body) {
        IngestionResult result = ingestion.ingest(parser.parse(body, Channel.REST));
        HttpStatus status = result.outcome() == Outcome.ACCEPTED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(TradeResponse.from(result.trade()));
    }
}
