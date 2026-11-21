package com.tradestore.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import com.tradestore.domain.AuditEvent;
import com.tradestore.domain.Channel;
import com.tradestore.domain.Outcome;
import com.tradestore.domain.Violation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** US-503: one CSV line per audit entry. */
class AuditCsvWriterTest {

    private static final Instant AT = Instant.parse("2026-11-19T10:15:30Z");

    private static String csv(AuditEvent event) throws IOException {
        StringWriter out = new StringWriter();
        AuditCsvWriter.writeHeader(out);
        AuditCsvWriter.writeRow(event, out);
        return out.toString();
    }

    @Test
    @Tag("US-503.1")
    void should_writeTheHeaderAndOneLinePerEntry_when_anEntryIsWritten() throws IOException {
        // Arrange
        AuditEvent event = new AuditEvent("e1", "T1", 2, Outcome.REJECTED,
                List.of(Violation.invalidField("bookId", "bookId is required"), Violation.lowerVersion(2, 3)),
                Channel.REST, AT, "{\"secret\":1}", null);

        // Act + Assert: reasons joined with ';', payload left out
        assertThat(csv(event)).isEqualTo("eventId,eventTimestamp,tradeId,version,outcome,channel,reasons\r\n"
                + "e1,2026-11-19T10:15:30Z,T1,2,REJECTED,REST,"
                + "INVALID_FIELD: bookId is required;LOWER_VERSION: version 2 is lower than stored version 3\r\n");
    }
}
