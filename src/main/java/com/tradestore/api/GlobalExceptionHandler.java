package com.tradestore.api;

import java.time.Clock;
import java.util.List;

import com.tradestore.domain.LowerVersionException;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.TradeRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the one API error shape (docs/05-lld.md section 7). A rejected trade lists every reason;
 * its status follows decision D6: 400 if any field is invalid (the request itself must be fixed), else 409 for
 * a lower version, else 422.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof PayloadTooLargeException tooLarge) {
                return tooLarge(tooLarge, request); // the chunked-body guard tripped inside the JSON parser
            }
        }
        return error(HttpStatus.BAD_REQUEST,
                List.of(new ApiErrorResponse.Reason("MALFORMED_REQUEST", null, "The request body is not a valid trade JSON document")),
                request);
    }

    /** A body over trade-store.api.max-request-bytes (US-401.6). */
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> tooLarge(PayloadTooLargeException e, HttpServletRequest request) {
        return error(HttpStatus.CONTENT_TOO_LARGE, List.of(new ApiErrorResponse.Reason("PAYLOAD_TOO_LARGE", null, e.getMessage())),
                request);
    }

    /**
     * Database unavailable (decision D7): no connection, a transient error, or a resource failure. Nothing was
     * stored or audited, so the client may retry. Logged with its cause.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, TransientDataAccessException.class,
            DataAccessResourceFailureException.class})
    public ResponseEntity<ApiErrorResponse> unavailable(RuntimeException e, HttpServletRequest request) {
        log.warn("Trade store unavailable for {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, List.of(new ApiErrorResponse.Reason("SERVICE_UNAVAILABLE", null,
                "The trade store is temporarily unavailable; nothing was stored. Please retry.")), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, List.of(new ApiErrorResponse.Reason("UNSUPPORTED_MEDIA_TYPE", null,
                "Send the trade as application/json")), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, List.of(new ApiErrorResponse.Reason("METHOD_NOT_ALLOWED", null,
                e.getMethod() + " is not supported here")), request);
    }

    /** Anything else, including permanent database errors: 500 in the standard shape, never "retry". */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error for {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, List.of(new ApiErrorResponse.Reason("INTERNAL_ERROR", null,
                "An unexpected error occurred; it has been logged.")), request);
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
