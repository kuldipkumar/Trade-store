package com.tradestore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
}
