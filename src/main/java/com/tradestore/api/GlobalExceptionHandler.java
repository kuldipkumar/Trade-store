package com.tradestore.api;

import java.time.Clock;
import java.util.List;

import com.tradestore.domain.LowerVersionException;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.TradeRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the one API error shape (docs/05-lld.md section 7). A rejected trade lists every reason;
 * its status follows decision D6: 400 if any field is invalid (the request itself must be fixed), else 409 for
 * a lower version, else 422.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(TradeRejectedException.class)
    public ResponseEntity<ApiErrorResponse> rejected(TradeRejectedException e, HttpServletRequest request) {
        return error(statusOf(e), e.violations().stream().map(ApiErrorResponse.Reason::from).toList(), request);
    }

    /** Malformed JSON or wrong JSON types: not a trade, so not audited (decision D3). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST,
                List.of(new ApiErrorResponse.Reason("MALFORMED_REQUEST", null, "The request body is not a valid trade JSON document")),
                request);
    }

    /** Stub: implemented test-first. */
    public ResponseEntity<ApiErrorResponse> tooLarge(PayloadTooLargeException e, HttpServletRequest request) {
        return null;
    }

    static HttpStatus statusOf(TradeRejectedException e) {
        if (e.reasons().contains(ReasonCode.INVALID_FIELD)) {
            return HttpStatus.BAD_REQUEST;
        }
        return e instanceof LowerVersionException ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_CONTENT;
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, List<ApiErrorResponse.Reason> reasons,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(clock.instant(), status.value(),
                status.getReasonPhrase(), reasons, request.getRequestURI()));
    }
}
