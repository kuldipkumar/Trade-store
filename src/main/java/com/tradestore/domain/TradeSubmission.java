package com.tradestore.domain;

/**
 * An unvalidated trade from either channel (A-21). Values are kept raw ({@code maturityDate} as text,
 * {@code version} nullable) so that bad values become INVALID_FIELD rejections rather than parse errors.
 *
 * @param rawPayload the original message or request body, used only for the audit trail
 */
public record TradeSubmission(
        String tradeId,
        Integer version,
        String counterPartyId,
        String bookId,
        String maturityDate,
        Channel channel,
        String rawPayload) {
}
