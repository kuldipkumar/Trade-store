package com.tradestore.api;

/** Stub: implemented test-first. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(long maxBytes) {
        super("The request body is larger than " + maxBytes + " bytes");
    }
}
