package com.tradestore.api;

/** A query parameter is out of range (for example page &lt; 0); mapped to 400 INVALID_PARAMETER. */
public class InvalidRequestParameterException extends RuntimeException {

    private final String parameter;

    public InvalidRequestParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
