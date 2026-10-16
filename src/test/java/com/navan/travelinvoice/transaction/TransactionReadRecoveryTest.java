package com.navan.travelinvoice.transaction;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.util.concurrent.CountDownLatch;
import java.time.Duration;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.RepeatedTest;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TransactionReadRecoveryTest.Hooks.class)
class TransactionReadRecoveryTest extends PostgresIntegrationTestSupport {
    @TestConfiguration(proxyBeanMethods = false)
    static class Hooks {
        @Bean Gate gate() { return new Gate(); }
        @Bean HibernatePropertiesCustomizer inspector(Gate gate) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", gate);
        }
    }

    static class Gate implements StatementInspector {
        volatile Predicate<String> match;
        volatile CountDownLatch reached;
        volatile CountDownLatch resume;

        void arm(Predicate<String> match) {
            reached = new CountDownLatch(1);
            resume = new CountDownLatch(1);
            this.match = match;
        }

        @Override
        public String inspect(String sql) {
            Predicate<String> predicate = match;
            if (predicate != null && predicate.test(sql.toLowerCase())) {
                match = null;
                reached.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("Gate was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return sql;
        }
    }

    @Autowired Gate gate;
    @Autowired DataSource dataSource;

    @Test
    void getUsesOneSnapshotWhenPatchCommitsAfterHeaderRead() throws Exception {
        var response = post(payload("flight"));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode before = json(response);
        long id = before.get("id").longValue();
        gate.arm(sql -> sql.startsWith("select") && sql.contains("from transaction_taxes"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var read = executor.submit(() -> get(id));
            try {
                assertThat(gate.reached.await(10, TimeUnit.SECONDS)).isTrue();
                var update = mapper.createObjectNode().put("total", 999);
                update.putArray("tax_lines").addObject().put("name", "New tax").put("amount", 99);
                update.putArray("fee_lines").addObject().put("name", "New fee").put("amount", 88);
                assertThat(patch(id, update).statusCode()).isEqualTo(200);
            } finally { gate.resume.countDown(); }
            var result = read.get(20, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(200);
            assertJsonEquivalent(json(result), before);
        } finally { gate.match = null; }
        assertThat(json(get(id)).get("total").decimalValue()).isEqualByComparingTo("999");
    }

    @Test
    void deleteBeforeAtomicIdentityResolutionAllowsCompleteRecreation() throws Exception {
        var input = payload("flight");
        var created = post(input);
        assertThat(created.statusCode()).isEqualTo(201);
        long oldId = json(created).get("id").longValue();
        // Identity is now resolved inside one atomic SQL statement, not via a
        // post-rollback SELECT. Pause before that statement and let DELETE win.
        gate.arm(sql -> sql.stripLeading().startsWith("insert into transactions"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var duplicate = executor.submit(() -> post(input));
            try {
                assertThat(gate.reached.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(delete(oldId).statusCode()).isEqualTo(204);
            } finally { gate.resume.countDown(); }
            var result = duplicate.get(20, TimeUnit.SECONDS);
            assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);
            long newId = json(result).get("id").longValue();
            assertThat(newId).isNotEqualTo(oldId);
            assertThat(get(oldId).statusCode()).isEqualTo(404);
            JsonNode stored = json(get(newId));
            for (String field : java.util.List.of("type", "external_id", "total", "metadata", "tax_lines", "fee_lines")) {
                assertJsonEquivalent(stored.get(field), input.get(field));
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE external_id = ?",
                    Integer.class, input.get("external_id").asString())).isEqualTo(1);
            assertThat(count("transaction_taxes")).isEqualTo(1);
            assertThat(count("transaction_fees")).isEqualTo(1);
        } finally { gate.match = null; }
    }

    @Test
    void duplicateNoOpUpdateIsRolledBackWithoutChangingStoredRow() throws Exception {
        var input = payload("flight");
        var first = post(input);
        assertThat(first.statusCode()).isEqualTo(201);
        long id = json(first).get("id").longValue();
        String version = jdbc.queryForObject("SELECT xmin::text FROM transactions WHERE id = ?", String.class, id);
        input.put("total", 999);
        var duplicate = post(input);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).get("existing_id").longValue()).isEqualTo(id);
        assertJsonEquivalent(json(get(id)), json(first));
        assertThat(jdbc.queryForObject("SELECT xmin::text FROM transactions WHERE id = ?", String.class, id)).isEqualTo(version);
    }

    @Test
    void duplicateCapturesExistingIdBeforeWaitingDeleteCanRemoveIt() throws Exception {
        var input = payload("flight");
        long id = json(post(input)).get("id").longValue();
        long key = 98241367;
        jdbc.execute("CREATE FUNCTION hold_duplicate_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(" + key + "); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER hold_duplicate_identity BEFORE UPDATE ON transactions FOR EACH ROW EXECUTE FUNCTION hold_duplicate_identity()");
        try (Connection control = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            try (var statement = control.createStatement()) { statement.executeQuery("SELECT pg_advisory_lock(" + key + ")").close(); }
            var duplicate = executor.submit(() -> post(input));
            java.util.concurrent.Future<java.net.http.HttpResponse<String>> deletion;
            try {
                awaitWaiters(1);
                deletion = executor.submit(() -> delete(id));
                awaitWaiters(2);
            } finally {
                try (var statement = control.createStatement()) { statement.executeQuery("SELECT pg_advisory_unlock(" + key + ")").close(); }
            }
            var response = duplicate.get(20, TimeUnit.SECONDS);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
            assertThat(json(response).get("existing_id").longValue()).isEqualTo(id);
            assertThat(deletion.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(count("transactions")).isZero();
            assertThat(count("transaction_taxes")).isZero();
            assertThat(count("transaction_fees")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER hold_duplicate_identity ON transactions");
            jdbc.execute("DROP FUNCTION hold_duplicate_identity()");
        }
    }

    @RepeatedTest(5)
    void duplicatePostAndDeleteHaveOnlyValidSerializedOutcomes() throws Exception {
        var input = payload("flight");
        long oldId = json(post(input)).get("id").longValue();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return post(input); });
            var deletion = executor.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return delete(oldId); });
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var result = creation.get(20, TimeUnit.SECONDS);
            assertThat(deletion.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(result.statusCode()).withFailMessage(result.body()).isIn(201, 409);
            if (result.statusCode() == 409) {
                assertThat(json(result).get("existing_id").longValue()).isEqualTo(oldId);
                assertThat(count("transactions")).isZero();
                assertThat(count("transaction_taxes")).isZero();
                assertThat(count("transaction_fees")).isZero();
            } else {
                assertThat(json(result).get("id").longValue()).isNotEqualTo(oldId);
                assertThat(count("transactions")).isEqualTo(1);
                assertThat(count("transaction_taxes")).isEqualTo(1);
                assertThat(count("transaction_fees")).isEqualTo(1);
            }
        }
    }

    private void awaitWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock'", Integer.class);
            if (waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + count + " database lock waiters");
    }
}
