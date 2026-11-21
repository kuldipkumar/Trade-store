package com.tradestore.api;

import com.tradestore.domain.Channel;
import com.tradestore.domain.TradeSubmission;
import com.tradestore.service.IngestionResult;
import com.tradestore.service.TradeIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Trade submission over REST (US-401). Uses the same ingestion use case as the stream (A-21). */
@RestController
@RequestMapping("/trades")
public class TradeController {

    private final TradeIngestionService ingestion;
    private final JsonMapper json;

    public TradeController(TradeIngestionService ingestion, JsonMapper json) {
        this.ingestion = ingestion;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<TradeResponse> submit(@RequestBody TradeRequest request) {
        TradeSubmission submission = new TradeSubmission(request.tradeId(), request.version(), request.counterPartyId(),
                request.bookId(), request.maturityDate(), Channel.REST, json.writeValueAsString(request));
        IngestionResult result = ingestion.ingest(submission);
        return ResponseEntity.status(HttpStatus.CREATED).body(TradeResponse.from(result.trade()));
    }
}
