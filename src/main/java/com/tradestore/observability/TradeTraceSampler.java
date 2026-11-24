package com.tradestore.observability;

import java.util.List;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;

/**
 * Keeps Tempo for trade traces (ADR-012): a database call with no span around it (for example the outbox poll's
 * SELECT ... FOR UPDATE) does not start a trace. Everything else is sampled at the configured ratio; child spans follow
 * their parent's decision. Scrapes and idle polls are skipped earlier, by TelemetryConfig's observation predicate.
 */
public final class TradeTraceSampler implements Sampler {

    private final Sampler ratio;

    private TradeTraceSampler(double probability) {
        this.ratio = Sampler.traceIdRatioBased(probability);
    }

    /** The sampler to install: parent-based, with this rule for root spans. */
    public static Sampler withProbability(double probability) {
        return Sampler.parentBased(new TradeTraceSampler(probability));
    }

    static boolean isNoise(String name, SpanKind kind) {
        return kind == SpanKind.CLIENT;   // span names are not final at sampling time, so only the kind is used
    }

    @Override
    public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
                                       Attributes attributes, List<LinkData> parentLinks) {
        return isNoise(name, spanKind) ? SamplingResult.drop()
                : ratio.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    @Override
    public String getDescription() {
        return "TradeTraceSampler{" + ratio.getDescription() + "}";
    }
}
