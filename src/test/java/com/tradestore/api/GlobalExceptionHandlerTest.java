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

    @Test
    @Tag("US-402.1")
    void should_notTalkAboutStoring_when_aReadFindsTheDatabaseUnavailable() {
        // Act
        ResponseEntity<ApiErrorResponse> response = handler.unavailable(new DataAccessResourceFailureException("down"),
                new MockHttpServletRequest("GET", "/trades/T1"));

        // Assert
        assertThat(response.getBody().reasons().getFirst().message()).doesNotContain("stored").contains("retry");
    }

    @Test
    @Tag("US-402.2")
    void should_notEchoTheRequestedId_when_aTradeIsNotFound() {
        // Act
        ResponseEntity<ApiErrorResponse> response = handler.notFound(
                new com.tradestore.domain.TradeNotFoundException("<script>x</script>"), new MockHttpServletRequest("GET", "/trades/x"));

        // Assert: the ID is not reflected in the message (it only appears in path)
        assertThat(response.getBody().reasons().getFirst().message()).doesNotContain("<script>");
    }

    @Test
    @Tag("US-402.1")
    @ExtendWith(OutputCaptureExtension.class)
    void should_keepSpringsClientErrorStatusWithoutLoggingAnError_when_noAcceptableRepresentationExists(CapturedOutput output) {
        // Act
        ResponseEntity<ApiErrorResponse> response = handler.unexpected(
                new org.springframework.web.HttpMediaTypeNotAcceptableException("No acceptable representation"), request);

        // Assert: 406, no JSON body forced on a client that cannot accept it, and no false ERROR
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNull();
        assertThat(output).doesNotContain("ERROR");
    }

    @Test
    @Tag("US-401.5")
    void should_returnTheStatusSpringAssigns_when_aRequestParameterIsMissing() {
        // Act
        ResponseEntity<ApiErrorResponse> response = handler.unexpected(
                new org.springframework.web.bind.MissingServletRequestParameterException("page", "int"), request);

        // Assert: a 4xx in the standard shape, not a 500
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().status()).isEqualTo(400);
    }

    @Test
    @Tag("US-401.5")
    @ExtendWith(OutputCaptureExtension.class)
    void should_return400WithoutLoggingAnError_when_theQueryStringIsMalformed(CapturedOutput output) {
        // Arrange: what Tomcat throws for "GET /trades?=&page=0", found by the ZAP scan
        var malformed = new org.apache.tomcat.util.http.InvalidParameterException(
                "Invalid chunk starting at byte [0] and ending at byte [1] with a value of [=] ignored", 400);

        // Act
        ResponseEntity<ApiErrorResponse> response = handler.malformedQuery(malformed, new MockHttpServletRequest("GET", "/trades"));

        // Assert: a client error in the standard shape, not a 500, and no false ERROR in the log
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().reasons().getFirst().reason()).isEqualTo("INVALID_PARAMETER");
        assertThat(output).doesNotContain("ERROR");
    }
}
