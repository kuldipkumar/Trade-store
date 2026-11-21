package com.tradestore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.tradestore.config.TradeStoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** US-401.6: bodies over the limit are refused, also when no Content-Length is sent (chunked). */
class RequestSizeLimitFilterTest {

    private final TradeStoreProperties properties = new Binder(new MapConfigurationPropertySource(
            Map.of("trade-store.api.max-request-bytes", "10"))).bindOrCreate("trade-store", TradeStoreProperties.class);
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(properties, JsonMapper.builder().build(),
            Clock.fixed(Instant.parse("2026-11-21T09:00:00Z"), ZoneOffset.UTC));

    private static MockHttpServletRequest chunked(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/trades") {
            @Override
            public long getContentLengthLong() {
                return -1; // Transfer-Encoding: chunked, no Content-Length
            }
        };
        request.setContent(body.getBytes());
        return request;
    }

    @Test
    @Tag("US-401.6")
    void should_stopReading_when_aChunkedBodyGrowsPastTheLimit() throws Exception {
        // Arrange: the downstream reads the whole body
        MockFilterChain readsEverything = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException {
                ((HttpServletRequest) request).getInputStream().readAllBytes();
            }
        };

        // Act + Assert: reading past 10 bytes fails with PayloadTooLargeException
        assertThatThrownBy(() -> filter.doFilter(chunked("x".repeat(11)), new MockHttpServletResponse(), readsEverything))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    @Tag("US-401.6")
    void should_passTheBodyThrough_when_aChunkedBodyIsWithinTheLimit() throws Exception {
        // Arrange
        StringBuilder seen = new StringBuilder();
        MockFilterChain recordsBody = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException {
                seen.append(new String(((HttpServletRequest) request).getInputStream().readAllBytes()));
            }
        };

        // Act
        filter.doFilter(chunked("0123456789"), new MockHttpServletResponse(), recordsBody);

        // Assert
        assertThat(seen.toString()).isEqualTo("0123456789");
    }
}
