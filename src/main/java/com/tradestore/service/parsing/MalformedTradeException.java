package com.tradestore.service.parsing;

/** The input is not a trade JSON object of the right shape: not JSON, not an object, or a field of the wrong JSON type. */
public class MalformedTradeException extends RuntimeException {

    public MalformedTradeException(String message) {
        super(message);
    }
}
