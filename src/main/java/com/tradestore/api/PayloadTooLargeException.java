package com.tradestore.api;

/** A request body is larger than {@code trade-store.api.max-request-bytes} (US-401.6); mapped to 413. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(long maxBytes) {
        super("The request body is larger than " + maxBytes + " bytes");
    }
}
