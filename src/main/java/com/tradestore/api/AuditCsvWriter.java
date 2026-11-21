package com.tradestore.api;

import java.io.IOException;
import java.io.Writer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tradestore.domain.AuditEvent;

/** Writes audit entries as CSV (US-503, docs/05-lld.md section 6.1). The payload is not exported. */
final class AuditCsvWriter {

    static final String HEADER = "eventId,eventTimestamp,tradeId,version,outcome,channel,reasons";

    private AuditCsvWriter() {
    }

    private static final String LINE_END = "\r\n"; // RFC 4180

    static void writeHeader(Writer out) throws IOException {
        out.write(HEADER + LINE_END);
    }

    static void writeRow(AuditEvent event, Writer out) throws IOException {
        // POISON and FAILED entries have no violations; their cause is the reason.
        String reasons = event.reasons().isEmpty() && event.error() != null ? event.error()
                : event.reasons().stream().map(v -> v.reason() + ": " + v.message()).collect(Collectors.joining(";"));
        out.write(Stream.of(event.eventId(), String.valueOf(event.eventTimestamp()), nullToEmpty(event.tradeId()),
                        event.version() == null ? "" : event.version().toString(), event.outcome().name(),
                        event.channel().name(), reasons)
                .map(AuditCsvWriter::cell).collect(Collectors.joining(",")) + LINE_END);
    }

    /** Formula characters neutralised; RFC 4180: a field with a comma, quote or line break is quoted, and its quotes doubled. */
    private static String cell(String raw) {
        // OWASP CSV injection: a leading = + - @ tab or CR makes spreadsheets evaluate the cell; ' shows it as text.
        String value = !raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0 ? "'" + raw : raw;
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
