package com.tradestore.observability;

import java.util.function.Function;

/**
 * The tracing operations the use cases need (A-46, ADR-012), kept small so services and adapters don't depend on a
 * tracing library: read the current W3C {@code traceparent}, and run work in a span.
 */
public interface TraceContext {

    /** The current span's W3C traceparent ({@code 00-<traceId>-<spanId>-<flags>}), or null outside a trace. */
    String currentTraceparent();

    /**
     * Runs {@code work} in a new span named {@code name}: a child of {@code parentTraceparent} when given (to continue
     * a trace across an asynchronous hand-off such as the outbox), otherwise a child of the current span.
     */
    <T> T inSpan(String name, String parentTraceparent, Function<SpanTags, T> work);

    /** Adds attributes to the running span, for example the trade ID so Tempo can find the trace. */
    interface SpanTags {
        void tag(String key, String value);
    }

    /** No tracing: for unit tests and anywhere tracing is not wired. */
    TraceContext NONE = new TraceContext() {
        @Override
        public String currentTraceparent() {
            return null;
        }

        @Override
        public <T> T inSpan(String name, String parentTraceparent, Function<SpanTags, T> work) {
            return work.apply((key, value) -> { });
        }
    };
}
