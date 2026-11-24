package com.tradestore.observability;

import javax.sql.DataSource;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.instrumentation.mongo.v3_1.MongoTelemetry;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Database spans and log export for traces (A-46, ADR-012). HTTP and Kafka spans come from Spring's own observations;
 * these add PostgreSQL statements, MongoDB commands and OTLP log records.
 */
@Configuration(proxyBeanMethods = false)
public class TelemetryConfig {

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
