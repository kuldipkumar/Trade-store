package com.tradestore.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A {@link TraceContext} for unit tests: records every span with its parent traceparent and tags. */
public class RecordingTraceContext implements TraceContext {

    /** One recorded span. */
    public record Recorded(String name, String parentTraceparent, Map<String, String> tags) {
    }

    private final List<Recorded> spans = new ArrayList<>();

    public List<Recorded> spans() {
        return spans;
    }

    @Override
    public String currentTraceparent() {
        return null;
    }

    @Override
    public <T> T inSpan(String name, String parentTraceparent, Function<SpanTags, T> work) {
        Map<String, String> tags = new LinkedHashMap<>();
        spans.add(new Recorded(name, parentTraceparent, tags));
        return work.apply(tags::put);
    }
}
