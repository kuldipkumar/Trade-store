package com.tradestore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;

/** Exception-to-status mapping that the end-to-end tests cannot reach easily (docs/05-lld.md section 7). */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(Instant.parse("2026-11-21T09:00:00Z"), ZoneOffset.UTC));
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trades");

    @Test
    @Tag("US-401.6")
    void should_return413_when_aChunkedBodyTooLargeSurfacesWhileParsing() {
        // Arrange: the size guard tripped inside the JSON parser
        HttpMessageNotReadableException e = new HttpMessageNotReadableException("I/O error while reading input message",
                new PayloadTooLargeException(1_048_576), mock(HttpInputMessage.class));

        // Act
        ResponseEntity<ApiErrorResponse> response = handler.unreadable(e, request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody().reasons()).extracting(ApiErrorResponse.Reason::reason).containsExactly("PAYLOAD_TOO_LARGE");
    }

    @Test
    @Tag("US-401.6")
    void should_return413_when_thePayloadTooLargeExceptionReachesTheHandlerDirectly() {
        // Act
        ResponseEntity<ApiErrorResponse> response = handler.tooLarge(new PayloadTooLargeException(1_048_576), request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    @Tag("US-401.1")
    void should_return503_when_theDatabaseIsUnavailable() {
        // Act (decision D7: nothing was stored or audited, the client may retry)
        ResponseEntity<ApiErrorResponse> response =
                handler.unavailable(new DataAccessResourceFailureException("PostgreSQL down"), request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().reasons()).extracting(ApiErrorResponse.Reason::reason).containsExactly("SERVICE_UNAVAILABLE");
    }

    @Test
    @Tag("US-401.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_return503AndLogTheCause_when_noDatabaseConnectionCanBeObtained(CapturedOutput output) {
        // Arrange: what TransactionTemplate throws when PostgreSQL is down (a TransactionException, not DataAccess)
        CannotCreateTransactionException e = new CannotCreateTransactionException("Could not open JDBC Connection",
                new java.sql.SQLTransientConnectionException("Connection refused"));

        // Act
        ResponseEntity<ApiErrorResponse> response = handler.unavailable(e, request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(output).contains("Connection refused");
    }

    @Test
    @Tag("US-401.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_return500NotRetryAndLog_when_theDatabaseRejectsTheWritePermanently(CapturedOutput output) {
        // Act: a permanent failure must not be presented as "temporarily unavailable, retry"
        ResponseEntity<ApiErrorResponse> response = handler.unexpected(
                new DataIntegrityViolationException("value too long for type character varying(64)"), request);

        // Assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().reasons()).extracting(ApiErrorResponse.Reason::reason).containsExactly("INTERNAL_ERROR");
        assertThat(output).contains("ERROR").contains("value too long");
    }

    @Test
    @Tag("US-401.1")
    void should_return500InTheStandardShape_when_anUnexpectedExceptionEscapes() {
        // Act
        ResponseEntity<ApiErrorResponse> response = handler.unexpected(new IllegalStateException("bug"), request);

        // Assert: the same error shape as every other error, without internal details
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().path()).isEqualTo("/trades");
        assertThat(response.getBody().reasons().getFirst().message()).doesNotContain("bug");
    }
}
