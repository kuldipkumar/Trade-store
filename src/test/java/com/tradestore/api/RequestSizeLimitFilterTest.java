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

    /** Runs the filter with a downstream that reads the body in the given way, and returns what it threw. */
    private Throwable readThrough(String body, ReadStrategy strategy) {
        MockFilterChain downstream = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws IOException {
                strategy.read((HttpServletRequest) request);
            }
        };
        try {
            filter.doFilter(chunked(body), new MockHttpServletResponse(), downstream);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    interface ReadStrategy {
        void read(HttpServletRequest request) throws IOException;
    }

    @Test
    @Tag("US-401.6")
    void should_limitTheBody_when_itIsReadThroughGetReader() {
        // Act + Assert
        assertThat(readThrough("x".repeat(11), r -> r.getReader().lines().count()))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    @Tag("US-401.6")
    void should_notResetTheAllowance_when_getInputStreamIsCalledAgain() {
        // Act: 6 bytes through one stream, then 5 more through a second call (11 in total, limit 10)
        Throwable thrown = readThrough("x".repeat(11), r -> {
            r.getInputStream().readNBytes(6);
            r.getInputStream().readAllBytes();
        });

        // Assert
        assertThat(thrown).isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    @Tag("US-401.6")
    void should_countBulkReads_when_theBodyIsReadInBlocks() {
        // Act: one bulk read of the whole 11-byte body
        Throwable thrown = readThrough("x".repeat(11), r -> r.getInputStream().read(new byte[64], 0, 64));

        // Assert
        assertThat(thrown).isInstanceOf(PayloadTooLargeException.class);
    }
}
