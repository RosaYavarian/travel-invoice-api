package com.navan.travelinvoice.transaction;

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

class TransactionMutationConcurrencyTest extends PostgresIntegrationTestSupport {
    private static final long GATE_KEY = 76243819L;
    @Autowired
    DataSource dataSource;

    @RepeatedTest(5)
    void concurrentFullPatchesNeverTearHeaderMetadataOrChildren() throws Exception {
        JsonNode before = createFlight();
        long id = before.get("id").longValue();
        ObjectNode a = replacement("A", "111.11");
        ObjectNode b = replacement("B", "222.22");
        List<HttpResponse<String>> responses = race(() -> patch(id, a), () -> patch(id, b));
        responses.forEach(response -> assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200));
        for (HttpResponse<String> response : responses) {
            JsonNode result = json(response);
            ObjectNode expected = result.get("total").decimalValue().compareTo(new BigDecimal("111.11")) == 0 ? a : b;
            assertFields(result, expected);
        }
        JsonNode stored = json(get(id));
        ObjectNode expected = stored.get("total").decimalValue().compareTo(new BigDecimal("111.11")) == 0 ? a : b;
        assertFields(stored, expected);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_taxes")).isEqualTo(2);
        assertThat(count("transaction_fees")).isEqualTo(2);
    }

    @RepeatedTest(5)
    void concurrentDisjointPatchesPreserveBothChanges() throws Exception {
        long id = createFlight().get("id").longValue();
        ObjectNode total = mapper.createObjectNode().put("total", new BigDecimal("123.45"));
        ObjectNode metadata = mapper.createObjectNode();
        metadata.set("metadata", replacement("new", "1.00").get("metadata"));
        List<HttpResponse<String>> responses = race(() -> patch(id, total), () -> patch(id, metadata));
        responses.forEach(response -> assertThat(response.statusCode()).isEqualTo(200));
        JsonNode stored = json(get(id));
        assertFields(stored, total);
        assertFields(stored, metadata);
        assertThat(count("transaction_taxes")).isEqualTo(1);
        assertThat(count("transaction_fees")).isEqualTo(1);
    }

    @RepeatedTest(5)
    void concurrentPatchDeleteIsAtomicWhicheverAcquiresTheLockFirst() throws Exception {
        long id = createFlight().get("id").longValue();
        ObjectNode input = replacement("patched", "333.33");
        List<HttpResponse<String>> responses = race(() -> patch(id, input), () -> delete(id));
        assertThat(responses.get(0).statusCode()).isIn(200, 404);
        if (responses.get(0).statusCode() == 200) assertFields(json(responses.get(0)), input);
        assertThat(responses.get(1).statusCode()).isEqualTo(204);
        assertThat(get(id).statusCode()).isEqualTo(404);
        assertThat(count("transactions")).isZero();
        assertThat(count("transaction_taxes")).isZero();
        assertThat(count("transaction_fees")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invoice-first-patch", "invoice-first-delete", "patch-first-invoice", "delete-first-invoice"})
    void invoiceCreationAndMutationFollowTheParentLockProtocol(String scenario) throws Exception {
        JsonNode before = createFlight();
        long id = before.get("id").longValue();
        boolean invoiceFirst = scenario.startsWith("invoice");
        boolean deleting = scenario.contains("delete");
        ObjectNode patchBody = replacement("updated", "444.44");
        ObjectNode invoiceBody = mapper.createObjectNode().put("transaction_id", id);
        Callable<HttpResponse<String>> invoiceCall = () -> post("/invoices", invoiceBody);
        Callable<HttpResponse<String>> mutationCall = () -> deleting ? delete(id) : patch(id, patchBody);
        String table = invoiceFirst ? "invoices" : "transactions";
        String operation = invoiceFirst ? "INSERT" : deleting ? "DELETE" : "UPDATE";
        // Pause the first HTTP operation AFTER it owns the source lock. The second
        // must wait for that same row, so both orderings are exercised deterministically.
        jdbc.execute("CREATE FUNCTION hold_mutation_race() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(" + GATE_KEY + "); IF TG_OP = 'DELETE' THEN RETURN OLD; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER hold_mutation_race BEFORE " + operation + " ON " + table + " FOR EACH ROW EXECUTE FUNCTION hold_mutation_race()");
        try {
            List<HttpResponse<String>> responses;
            try (Connection gate = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
                try (var statement = gate.createStatement()) {
                    statement.executeQuery("SELECT pg_advisory_lock(" + GATE_KEY + ")").close();
                }
                var first = executor.submit(invoiceFirst ? invoiceCall : mutationCall);
                java.util.concurrent.Future<HttpResponse<String>> second;
                try {
                    awaitWaitingRequests(1);
                    second = executor.submit(invoiceFirst ? mutationCall : invoiceCall);
                    awaitWaitingRequests(2);
                    assertThat(first.isDone()).isFalse();
                    assertThat(second.isDone()).isFalse();
                } finally {
                    try (var statement = gate.createStatement()) {
                        statement.executeQuery("SELECT pg_advisory_unlock(" + GATE_KEY + ")").close();
                    }
                }
                responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            }
            HttpResponse<String> invoiceResponse = responses.get(invoiceFirst ? 0 : 1);
            HttpResponse<String> mutationResponse = responses.get(invoiceFirst ? 1 : 0);
            if (invoiceFirst) {
                assertThat(invoiceResponse.statusCode()).withFailMessage(invoiceResponse.body()).isEqualTo(201);
                assertThat(mutationResponse.statusCode()).withFailMessage(mutationResponse.body()).isEqualTo(409);
                assertJsonEquivalent(json(get(id)), before);
                JsonNode invoice = json(invoiceResponse);
                assertJsonEquivalent(json(get("/invoices/" + invoice.get("id").longValue())), invoice);
                for (String field : List.of("total", "metadata", "tax_lines", "fee_lines")) {
                    assertJsonEquivalent(invoice.at("/lines/0").get(field), before.get(field));
                }
                assertThat(count("transactions")).isEqualTo(1);
                assertThat(count("transaction_taxes")).isEqualTo(1);
                assertThat(count("transaction_fees")).isEqualTo(1);
            } else if (deleting) {
                assertThat(mutationResponse.statusCode()).isEqualTo(204);
                assertThat(invoiceResponse.statusCode()).withFailMessage(invoiceResponse.body()).isEqualTo(404);
                assertThat(get(id).statusCode()).isEqualTo(404);
                assertThat(count("transactions")).isZero();
                assertThat(count("transaction_taxes")).isZero();
                assertThat(count("transaction_fees")).isZero();
            } else {
                assertThat(mutationResponse.statusCode()).withFailMessage(mutationResponse.body()).isEqualTo(200);
                assertThat(invoiceResponse.statusCode()).withFailMessage(invoiceResponse.body()).isEqualTo(201);
                assertFields(json(get(id)), patchBody);
                assertFields(json(invoiceResponse).at("/lines/0"), patchBody, false);
                assertThat(json(invoiceResponse).get("grand_total").decimalValue()).isEqualByComparingTo("444.44");
                assertThat(count("transaction_taxes")).isEqualTo(2);
                assertThat(count("transaction_fees")).isEqualTo(2);
            }
            assertThat(count("invoices")).isEqualTo(!invoiceFirst && deleting ? 0 : 1);
            assertThat(count("invoice_line_snapshots")).isEqualTo(!invoiceFirst && deleting ? 0 : 1);
        } finally {
            jdbc.execute("DROP TRIGGER hold_mutation_race ON " + table);
            jdbc.execute("DROP FUNCTION hold_mutation_race()");
        }
    }

    private void awaitWaitingRequests(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting >= count) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Expected " + count + " HTTP database operations waiting for locks");
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> firstCall,
                                             Callable<HttpResponse<String>> secondCall) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return firstCall.call();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return secondCall.call();
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private JsonNode createFlight() throws Exception {
        HttpResponse<String> response = post(payload("flight"));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response);
    }

    private ObjectNode replacement(String marker, String total) {
        ObjectNode input = mapper.createObjectNode().put("total", new BigDecimal(total))
                .put("occurred_at", "2026-04-01T10:00:00Z");
        ObjectNode metadata = (ObjectNode) payload("flight").get("metadata");
        metadata.put("marker", marker);
        input.set("metadata", metadata);
        input.putArray("tax_lines").addObject().put("name", marker + " tax 1").put("amount", new BigDecimal("1.23"));
        input.withArray("tax_lines").addObject().put("name", marker + " tax 2").put("amount", new BigDecimal("2.34"));
        input.putArray("fee_lines").addObject().put("name", marker + " fee 1").put("amount", new BigDecimal("3.45"));
        input.withArray("fee_lines").addObject().put("name", marker + " fee 2").put("amount", new BigDecimal("4.56"));
        return input;
    }

    private void assertFields(JsonNode actual, ObjectNode expected) {
        assertFields(actual, expected, true);
    }

    private void assertFields(JsonNode actual, ObjectNode expected, boolean includeOccurredAt) {
        expected.properties().forEach(entry -> {
            if (includeOccurredAt || !entry.getKey().equals("occurred_at")) {
                assertJsonEquivalent(actual.get(entry.getKey()), entry.getValue());
            }
        });
    }
}
