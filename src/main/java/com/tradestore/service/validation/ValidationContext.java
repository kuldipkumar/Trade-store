package com.tradestore.service.validation;

import java.time.LocalDate;
import java.util.Optional;

import com.tradestore.domain.StoredTrade;
import com.tradestore.domain.TradeSubmission;

/**
 * Everything a validator may look at.
 *
 * @param stored the currently stored version of the trade, if any
 * @param today  the local date from the injected Clock (A-16)
 */
public record ValidationContext(TradeSubmission submission, Optional<StoredTrade> stored, LocalDate today) {
}
