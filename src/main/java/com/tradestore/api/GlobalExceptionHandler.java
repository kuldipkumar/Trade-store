package com.tradestore.api;

import java.time.Clock;
import java.util.List;

import com.tradestore.domain.LowerVersionException;
import com.tradestore.domain.ReasonCode;
import com.tradestore.domain.TradeNotFoundException;
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
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** Not a trade JSON object, or a field of the wrong JSON type: not a trade, so not audited (decision D3). */
    @ExceptionHandler(MalformedTradeException.class)
    public ResponseEntity<ApiErrorResponse> malformed(MalformedTradeException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, List.of(new ApiErrorResponse.Reason("MALFORMED_REQUEST", null, e.getMessage())),
                request);
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
        String message = "GET".equals(request.getMethod())
                ? "The trade store is temporarily unavailable. Please retry."
                : "The trade store is temporarily unavailable; nothing was stored. Please retry.";
        return error(HttpStatus.SERVICE_UNAVAILABLE, List.of(new ApiErrorResponse.Reason("SERVICE_UNAVAILABLE", null, message)),
                request);
    }

    /** An out-of-range query parameter (US-403). */
    @ExceptionHandler(InvalidRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameter(InvalidRequestParameterException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, List.of(new ApiErrorResponse.Reason("INVALID_PARAMETER", e.parameter(),
                e.getMessage())), request);
    }

    /** An unknown trade (US-402.2, US-404.2). */
    @ExceptionHandler(TradeNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> notFound(TradeNotFoundException e, HttpServletRequest request) {
        // A fixed message: the requested ID is not reflected back (it is in path).
        return error(HttpStatus.NOT_FOUND, List.of(new ApiErrorResponse.Reason("NOT_FOUND", "tradeId",
                "No trade is stored with this ID")), request);
    }

    /** An unknown path: 404, not the catch-all 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> noRoute(NoResourceFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, List.of(new ApiErrorResponse.Reason("NOT_FOUND", null,
                "No endpoint " + request.getMethod() + " " + request.getRequestURI())), request);
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
        if (e instanceof ErrorResponse springError) {
            // A Spring MVC client error that knows its own status (406, missing parameter, ...): not a bug.
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            log.debug("{} for {} {}: {}", status.value(), request.getMethod(), request.getRequestURI(), e.toString());
            if (status == HttpStatus.NOT_ACCEPTABLE) {
                return ResponseEntity.status(status).build(); // the client cannot accept our JSON error body
            }
            return error(status, List.of(new ApiErrorResponse.Reason(status.name(), null, status.getReasonPhrase())), request);
        }
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
