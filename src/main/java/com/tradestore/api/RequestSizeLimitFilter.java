package com.tradestore.api;

import java.io.IOException;
import java.time.Clock;
import java.util.List;

import com.tradestore.config.TradeStoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Refuses request bodies larger than {@code trade-store.api.max-request-bytes} with 413 (US-401.6, A-47), in the
 * standard error shape. Runs before the controllers, so it writes the response itself.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final JsonMapper json;
    private final Clock clock;

    public RequestSizeLimitFilter(TradeStoreProperties properties, JsonMapper json, Clock clock) {
        this.maxBytes = properties.api().maxRequestBytes();
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            HttpStatus status = HttpStatus.CONTENT_TOO_LARGE;
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), new ApiErrorResponse(clock.instant(), status.value(),
                    status.getReasonPhrase(), List.of(new ApiErrorResponse.Reason("PAYLOAD_TOO_LARGE", null,
                            "The request body is larger than " + maxBytes + " bytes")), request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
    }
}
