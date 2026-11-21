package com.tradestore.api;

import java.time.Clock;

import com.tradestore.domain.LowerVersionException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps exceptions to the one API error shape (docs/05-lld.md section 7). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(LowerVersionException.class)
    public ResponseEntity<ApiErrorResponse> lowerVersion(LowerVersionException e, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new ApiErrorResponse(clock.instant(), status.value(),
                status.getReasonPhrase(), e.violations().stream().map(ApiErrorResponse.Reason::from).toList(),
                request.getRequestURI()));
    }
}
