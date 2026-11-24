package com.tradestore.observability;

import javax.sql.DataSource;

import com.tradestore.scheduler.OutboxRelayScheduler;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.instrumentation.mongo.v3_1.MongoTelemetry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Database spans and log export for traces (A-46, ADR-012). HTTP and Kafka spans come from Spring's own observations;
 * these add PostgreSQL statements, MongoDB commands and OTLP log records.
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryConfig {

    /** Root spans of scrapes, idle polls and orphan database calls are dropped; see {@link TradeTraceSampler}. */
    @Bean
    Sampler otelSampler(@Value("${management.tracing.sampling.probability:0.1}") double probability) {
        return TradeTraceSampler.withProbability(probability);
    }

    /**
     * No observation (so no trace) for Prometheus scrapes, health probes or the outbox relay's idle poll: they would
     * add tens of thousands of empty traces a day. Their spans are dropped here because span names are not final when
     * the sampler runs. The relay's real work is traced per event as {@code outbox.relay}.
     */
    @Bean
    ObservationPredicate noTracesForScrapesAndPolls() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext http) {
                return !http.getCarrier().getRequestURI().startsWith("/actuator");
            }
            if (context instanceof ScheduledTaskObservationContext task) {
                return !OutboxRelayScheduler.class.isAssignableFrom(task.getTargetClass());
            }
            return true;
        };
    }

    /** One span per SQL statement (sanitised: no values), as children of the current span. */
    @Bean
    static BeanPostProcessor tracedDataSource(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof OpenTelemetryDataSource)) {
                    return JdbcTelemetry.create(openTelemetry.getObject()).wrap(dataSource);
                }
                return bean;
            }
        };
    }

    /** One span per MongoDB command (insert, find, aggregate). */
    @Bean
    MongoClientSettingsBuilderCustomizer tracedMongo(OpenTelemetry openTelemetry) {
        return settings -> settings.addCommandListener(MongoTelemetry.create(openTelemetry).createCommandListener());
    }

    /** Connects the Logback appender (logback-spring.xml) to the SDK, so log records are exported over OTLP. */
    @Bean
    InitializingBean openTelemetryLogAppender(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
