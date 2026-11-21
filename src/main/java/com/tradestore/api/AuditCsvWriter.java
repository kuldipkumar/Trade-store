package com.tradestore.api;

import java.io.IOException;
import java.io.Writer;

import com.tradestore.domain.AuditEvent;

/** Writes audit entries as CSV (US-503, docs/05-lld.md section 6.1). The payload is not exported. */
final class AuditCsvWriter {

    static final String HEADER = "eventId,eventTimestamp,tradeId,version,outcome,channel,reasons";

    private AuditCsvWriter() {
    }

    static void writeHeader(Writer out) throws IOException {
    }

    static void writeRow(AuditEvent event, Writer out) throws IOException {
    }
}
