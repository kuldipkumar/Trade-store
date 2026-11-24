package com.tradestore.observability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A {@link TraceContext} for unit tests: records every span with its parent traceparent and tags. */
public class RecordingTraceContext implements TraceContext {

    /** One recorded span. */
    public static final class Recorded {
        private final String name;
        private final String parentTraceparent;
        private final Map<String, String> tags;
        private boolean failed;

        Recorded(String name, String parentTraceparent, Map<String, String> tags) {
            this.name = name;
            this.parentTraceparent = parentTraceparent;
            this.tags = tags;
        }

        public String name() { return name; }
        public String parentTraceparent() { return parentTraceparent; }
        public Map<String, String> tags() { return tags; }
        /** True when the work threw out of the span, which a real tracer records as an error span. */
        public boolean failed() { return failed; }
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
        Recorded recorded = new Recorded(name, parentTraceparent, tags);
        spans.add(recorded);
        try {
            return work.apply(tags::put);
        } catch (RuntimeException | Error failure) {
            recorded.failed = true;
            throw failure;
        }
    }
}
