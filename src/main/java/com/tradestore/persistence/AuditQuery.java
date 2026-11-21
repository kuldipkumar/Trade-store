package com.tradestore.persistence;

import java.time.Instant;

import com.tradestore.domain.Outcome;

/**
 * Filters for the audit report (US-502). Every field is optional (null means no filter).
 *
 * @param from    first instant included
 * @param until   first instant excluded
 * @param tradeId only this trade
 * @param outcome only this outcome
 */
public record AuditQuery(Instant from, Instant until, String tradeId, Outcome outcome) {
}
