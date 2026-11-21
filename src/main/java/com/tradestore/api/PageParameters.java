package com.tradestore.api;

/** Shared checks for paged endpoints (US-403, US-502). */
final class PageParameters {

    private PageParameters() {
    }

    /** @throws InvalidRequestParameterException if page is negative or size is below 1 */
    static void validate(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestParameterException("page", "page must be 0 or greater");
        }
        if (size < 1) {
            throw new InvalidRequestParameterException("size", "size must be 1 or greater");
        }
    }
}
