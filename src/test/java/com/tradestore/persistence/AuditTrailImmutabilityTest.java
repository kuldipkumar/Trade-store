package com.tradestore.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.tradestore.persistence.nosql.MongoAuditEventStore;
import com.tradestore.persistence.nosql.MongoTradeHistoryStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * US-501.2 / A-45: the audit trail and trade history are append-only. Neither the ports nor their MongoDB
 * implementations offer any operation besides inserting and reading.
 */
class AuditTrailImmutabilityTest {

    /** Exactly the operations the LLD allows (docs/05-lld.md section 1); anything else is a mutation path. */
    private static final Set<String> ALLOWED = Set.of("insert", "find", "findByTradeId", "countByOutcome", "stream");
    private static final Predicate<String> INSERT_OR_READ = ALLOWED::contains;

    /** Public methods, inherited ones included; java.lang.Object's methods are ignored. */
    private static List<String> publicMethods(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(m -> m.getDeclaringClass() != Object.class && !m.isSynthetic() && !m.isBridge())
                .map(Method::getName)
                .toList();
    }

    @Test
    @Tag("US-501.2")
    void should_offerOnlyInsertAndReadOperations_when_theAuditAndHistoryPortsAreInspected() {
        // Act + Assert
        assertThat(publicMethods(AuditEventStore.class)).isNotEmpty().allMatch(INSERT_OR_READ);
        assertThat(publicMethods(TradeHistoryStore.class)).isNotEmpty().allMatch(INSERT_OR_READ);
    }

    @Test
    @Tag("US-501.2")
    void should_offerOnlyInsertAndReadOperations_when_theMongoImplementationsAreInspected() {
        // Act + Assert
        assertThat(publicMethods(MongoAuditEventStore.class)).isNotEmpty().allMatch(INSERT_OR_READ);
        assertThat(publicMethods(MongoTradeHistoryStore.class)).isNotEmpty().allMatch(INSERT_OR_READ);
    }
}
