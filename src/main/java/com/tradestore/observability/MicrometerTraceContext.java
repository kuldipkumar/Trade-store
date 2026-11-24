package com.tradestore.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

/** {@link TraceContext} on Micrometer Tracing (OpenTelemetry bridge); W3C trace context propagation. */
@Component
public class MicrometerTraceContext implements TraceContext {

    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public String currentTraceparent() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    @Override
    public <T> T inSpan(String name, String parentTraceparent, Function<SpanTags, T> work) {
        Span span = parentTraceparent == null
                ? tracer.nextSpan().name(name).start()
                : propagator.extract(Map.of(TRACEPARENT, parentTraceparent), Map::get).name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.apply(span::tag);
        } catch (RuntimeException | Error failure) {
            span.error(failure);
            throw failure;
        } finally {
            span.end();
        }
    }
}
