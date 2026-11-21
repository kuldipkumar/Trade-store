package com.tradestore.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The JSON body of {@code POST /trades} (docs/05-lld.md section 6.1). Values are kept raw, as in the stream
 * message, so field problems become INVALID_FIELD rejections with the same rules on both channels (A-21).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TradeRequest(String tradeId, Integer version, String counterPartyId, String bookId, String maturityDate) {
}
