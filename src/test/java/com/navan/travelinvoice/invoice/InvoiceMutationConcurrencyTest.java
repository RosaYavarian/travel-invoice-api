package com.navan.travelinvoice.invoice;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceMutationConcurrencyTest extends PostgresIntegrationTestSupport {
    @Autowired
    DataSource dataSource;

    @RepeatedTest(5)
    void sameInvoicePatchesSerializeWithoutMixingSnapshotSets() throws Exception {
        List<JsonNode> sources = sources();
        JsonNode original = invoice(List.of(sources.get(0)));
        long id = original.get("id").longValue();
        var a = body(List.of(sources.get(0), sources.get(1)));
        var b = body(List.of(sources.get(1), sources.get(2)));
        List<HttpResponse<String>> responses = race(() -> patch(path(id), a), () -> patch(path(id), b));
        for (HttpResponse<String> response : responses) {
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            JsonNode result = json(response);
            List<JsonNode> expected = result.at("/lines/0/transaction_id").longValue() == sources.get(0).get("id").longValue()
                    ? List.of(sources.get(0), sources.get(1)) : List.of(sources.get(1), sources.get(2));
            assertInvoice(result, expected);
        }
        JsonNode stored = json(get(path(id)));
        assertThat(responses.stream().anyMatch(response -> json(response).equals(stored))).isTrue();
        assertThat(count("invoice_line_snapshots")).isEqualTo(2);
    }

    @RepeatedTest(5)
    void patchDeleteOnSameInvoiceIsAtomicInEitherOrder() throws Exception {
        List<JsonNode> sources = sources();
        long id = invoice(List.of(sources.get(0))).get("id").longValue();
        List<HttpResponse<String>> responses = race(() -> patch(path(id), body(List.of(sources.get(1)))), () -> delete(path(id)));
        assertThat(responses.get(0).statusCode()).isIn(200, 404);
        if (responses.get(0).statusCode() == 200) assertInvoice(json(responses.get(0)), List.of(sources.get(1)));
        assertThat(responses.get(1).statusCode()).isEqualTo(204);
        assertThat(get(path(id)).statusCode()).isEqualTo(404);
        assertThat(count("invoices")).isZero();
        assertThat(count("invoice_line_snapshots")).isZero();
        assertThat(count("transactions")).isEqualTo(3);
    }

    @RepeatedTest(5)
    void differentInvoicesSwappingSourcesDoNotDeadlockOnForeignKeyChecks() throws Exception {
        List<JsonNode> sources = sources();
        JsonNode a = invoice(List.of(sources.get(0), sources.get(1)));
        JsonNode b = invoice(List.of(sources.get(1), sources.get(2)));
        var responses = race(() -> patch(path(a.get("id").longValue()), body(List.of(sources.get(1), sources.get(2)))),
                () -> patch(path(b.get("id").longValue()), body(List.of(sources.get(0), sources.get(1)))));
        assertThat(responses.get(0).statusCode()).withFailMessage(responses.get(0).body()).isEqualTo(200);
        assertThat(responses.get(1).statusCode()).withFailMessage(responses.get(1).body()).isEqualTo(200);
        assertInvoice(json(get(path(a.get("id").longValue()))), List.of(sources.get(1), sources.get(2)));
        assertInvoice(json(get(path(b.get("id").longValue()))), List.of(sources.get(0), sources.get(1)));
        assertThat(count("invoice_line_snapshots")).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace-patch", "replace-delete", "delete-patch", "delete-delete",
            "replace-patch-source-first", "replace-delete-source-first", "delete-patch-source-first", "delete-delete-source-first"})
    void queuedInvoiceRemovalAndSourceMutationAcceptActualCommitOrdering(String scenario) throws Exception {
        List<JsonNode> sources = sources();
        JsonNode original = invoice(List.of(sources.get(0)));
        long invoiceId = original.get("id").longValue();
        long sourceId = sources.get(0).get("id").longValue();
        boolean deletingInvoice = scenario.startsWith("delete");
        boolean deletingSource = scenario.split("-")[1].equals("delete");
        Callable<HttpResponse<String>> invoiceCall = () -> deletingInvoice ? delete(path(invoiceId))
                : patch(path(invoiceId), body(List.of(sources.get(1))));
        Callable<HttpResponse<String>> sourceCall = () -> deletingSource ? delete(sourceId)
                : patch(sourceId, mapper.createObjectNode().put("total", 444));
        List<HttpResponse<String>> responses = scenario.endsWith("source-first")
                ? raceBehindSourceLock(sourceId, sourceCall, invoiceCall).reversed()
                : raceBehindSourceLock(sourceId, invoiceCall, sourceCall);
        assertThat(responses.get(0).statusCode()).withFailMessage(responses.get(0).body()).isEqualTo(deletingInvoice ? 204 : 200);
        // If source mutation observes the old committed membership it must reject;
        // if removal commits first it may succeed. Neither ordering is an error.
        assertThat(responses.get(1).statusCode()).isIn(409, deletingSource ? 204 : 200);
        if (responses.get(1).statusCode() == 409) assertJsonEquivalent(json(get(sourceId)), sources.get(0));
        else if (deletingSource) assertThat(get(sourceId).statusCode()).isEqualTo(404);
        else assertThat(json(get(sourceId)).get("total").decimalValue()).isEqualByComparingTo("444");
        // Regardless of initial race order, membership is now gone and mutation is allowed.
        if (!(deletingSource && responses.get(1).statusCode() == 204)) {
            assertThat(patch(sourceId, mapper.createObjectNode().put("total", 555)).statusCode()).isEqualTo(200);
            assertThat(delete(sourceId).statusCode()).isEqualTo(204);
        }
        if (deletingInvoice) {
            assertThat(get(path(invoiceId)).statusCode()).isEqualTo(404);
            assertThat(count("invoice_line_snapshots")).isZero();
        } else assertInvoice(json(get(path(invoiceId))), List.of(sources.get(1)));
        assertThat(get(sources.get(1).get("id").longValue()).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"patch", "delete", "patch-source-first", "delete-source-first"})
    void addingSourceRacesWithSourceMutationWithoutForeignKeyDeadlocks(String operation) throws Exception {
        List<JsonNode> sources = sources();
        JsonNode original = invoice(List.of(sources.get(0)));
        long id = original.get("id").longValue();
        long sourceId = sources.get(1).get("id").longValue();
        boolean deleting = operation.startsWith("delete");
        ObjectNode change = mapper.createObjectNode().put("total", new BigDecimal("444.44"));
        ObjectNode metadata = (ObjectNode) payload("hotel").get("metadata");
        metadata.put("marker", "changed");
        change.set("metadata", metadata);
        change.putArray("tax_lines").addObject().put("name", "New tax").put("amount", new BigDecimal("1.23"));
        change.putArray("fee_lines").addObject().put("name", "New fee").put("amount", new BigDecimal("2.34"));
        Callable<HttpResponse<String>> invoiceCall = () -> patch(path(id), body(List.of(sources.get(1))));
        Callable<HttpResponse<String>> sourceCall = () -> deleting ? delete(sourceId) : patch(sourceId, change);
        var responses = operation.endsWith("source-first")
                ? raceBehindSourceLock(sourceId, sourceCall, invoiceCall).reversed()
                : raceBehindSourceLock(sourceId, invoiceCall, sourceCall);
        int invoiceStatus = responses.get(0).statusCode();
        int sourceStatus = responses.get(1).statusCode();
        if (deleting) {
            assertThat(List.of(invoiceStatus, sourceStatus)).isIn(List.of(200, 409), List.of(404, 204));
            if (invoiceStatus == 404) {
                assertJsonEquivalent(json(get(path(id))), original);
                assertThat(get(sourceId).statusCode()).isEqualTo(404);
            } else {
                assertInvoice(json(get(path(id))), List.of(sources.get(1)));
                assertThat(get(sourceId).statusCode()).isEqualTo(200);
            }
        } else {
            assertThat(invoiceStatus).withFailMessage(responses.get(0).body()).isEqualTo(200);
            assertThat(sourceStatus).isIn(200, 409);
            JsonNode expected = sourceStatus == 200 ? json(responses.get(1)) : sources.get(1);
            assertInvoice(json(get(path(id))), List.of(expected));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"patch", "delete"})
    void sourceMutationWaitsUntilInvoiceDeleteCommits(String operation) throws Exception {
        List<JsonNode> sources = sources();
        long invoiceId = invoice(List.of(sources.get(0))).get("id").longValue();
        long sourceId = sources.get(0).get("id").longValue();
        final long gateKey = 81273509;
        jdbc.execute("CREATE FUNCTION pause_invoice_snapshot_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(" + gateKey + "); RETURN OLD; END $$");
        jdbc.execute("CREATE TRIGGER pause_invoice_snapshot_delete BEFORE DELETE ON invoice_line_snapshots FOR EACH ROW EXECUTE FUNCTION pause_invoice_snapshot_delete()");
        try (Connection gate = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            try (var statement = gate.createStatement()) { statement.executeQuery("SELECT pg_advisory_lock(" + gateKey + ")").close(); }
            var first = executor.submit(() -> delete(path(invoiceId)));
            java.util.concurrent.Future<HttpResponse<String>> second;
            try {
                awaitWaiters(1);
                second = executor.submit(() -> operation.equals("delete") ? delete(sourceId)
                        : patch(sourceId, mapper.createObjectNode().put("total", 444)));
                awaitWaiters(2);
                assertThat(first.isDone()).isFalse();
                assertThat(second.isDone()).isFalse();
            } finally {
                try (var statement = gate.createStatement()) { statement.executeQuery("SELECT pg_advisory_unlock(" + gateKey + ")").close(); }
            }
            assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(204);
            assertThat(second.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(operation.equals("delete") ? 204 : 200);
            assertThat(count("invoice_line_snapshots")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER pause_invoice_snapshot_delete ON invoice_line_snapshots");
            jdbc.execute("DROP FUNCTION pause_invoice_snapshot_delete()");
        }
    }

    @RepeatedTest(5)
    void invoiceCreateRacingWithDeletePreservesReferencesWithoutDeadlock() throws Exception {
        List<JsonNode> sources = sources();
        JsonNode original = invoice(List.of(sources.get(0), sources.get(1)));
        var responses = race(() -> delete(path(original.get("id").longValue())),
                () -> post("/invoices", body(List.of(sources.get(1), sources.get(0)))));
        assertThat(responses.get(0).statusCode()).isEqualTo(204);
        assertThat(responses.get(1).statusCode()).withFailMessage(responses.get(1).body()).isEqualTo(201);
        assertThat(count("invoices")).isEqualTo(1);
        assertThat(count("invoice_line_snapshots")).isEqualTo(2);
        assertThat(delete(sources.get(0).get("id").longValue()).statusCode()).isEqualTo(409);
    }

    private List<HttpResponse<String>> raceBehindSourceLock(long sourceId,
            Callable<HttpResponse<String>> firstCall, Callable<HttpResponse<String>> secondCall) throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT id FROM transactions WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, sourceId);
                statement.executeQuery().close();
            }
            var first = executor.submit(firstCall);
            java.util.concurrent.Future<HttpResponse<String>> second;
            try {
                awaitWaiters(1);
                second = executor.submit(secondCall);
                awaitWaiters(2);
            } finally { blocker.commit(); }
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private void awaitWaiters(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + count + " database lock waiters");
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> a, Callable<HttpResponse<String>> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return a.call(); });
            var second = executor.submit(() -> { ready.countDown(); assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return b.call(); });
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private List<JsonNode> sources() throws Exception {
        var result = new java.util.ArrayList<JsonNode>();
        for (String name : List.of("flight", "hotel", "rail")) {
            HttpResponse<String> response = post(payload(name));
            assertThat(response.statusCode()).isEqualTo(201);
            result.add(json(response));
        }
        return result;
    }

    private JsonNode invoice(List<JsonNode> sources) throws Exception {
        HttpResponse<String> response = post("/invoices", body(sources));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response);
    }

    private ObjectNode body(List<JsonNode> sources) {
        ObjectNode body = mapper.createObjectNode();
        var ids = body.putArray("transaction_ids");
        sources.forEach(source -> ids.add(source.get("id").longValue()));
        return body;
    }

    private String path(long id) { return "/invoices/" + id; }

    private void assertInvoice(JsonNode actual, List<JsonNode> sources) {
        assertThat(actual.get("lines").size()).isEqualTo(sources.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < sources.size(); i++) {
            JsonNode source = sources.get(i);
            sum = sum.add(source.get("total").decimalValue());
            JsonNode line = actual.get("lines").get(i);
            assertThat(line.get("transaction_id")).isEqualTo(source.get("id"));
            for (String field : List.of("type", "currency", "total", "metadata", "tax_lines", "fee_lines")) {
                assertJsonEquivalent(line.get(field), source.get(field));
            }
        }
        assertThat(actual.get("grand_total").decimalValue()).isEqualByComparingTo(sum);
    }
}
