package com.navan.travelinvoice.invoice;

import com.navan.travelinvoice.model.dto.transaction.FeeLine;
import com.navan.travelinvoice.model.dto.transaction.TaxLine;
import com.navan.travelinvoice.model.entity.invoice.InvoiceLineSnapshot;
import com.navan.travelinvoice.model.entity.transaction.TransactionType;
import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceIntegrationTest extends PostgresIntegrationTestSupport {
    @Autowired
    DataSource dataSource;

    @Test
    void createsAndRetrievesSingleTransactionInvoice() throws Exception {
        JsonNode source = createTransaction(payload("flight"));
        HttpResponse<String> created = post("/invoices", mapper.createObjectNode()
                .put("transaction_id", source.get("id").longValue()));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode invoice = json(created);
        assertThat(invoice.get("id").longValue()).isPositive();
        assertThat(invoice.get("created_at").isString()).isTrue();
        assertThat(created.headers().firstValue("Location")).contains("/invoices/" + invoice.get("id").longValue());
        assertThat(invoice.get("currency").asString()).isEqualTo("EUR");
        assertThat(invoice.get("grand_total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(invoice.get("lines").size()).isEqualTo(1);
        assertSnapshot(invoice.at("/lines/0"), source);
        assertJsonEquivalent(invoice.get("tax_rollup"), source.get("tax_lines"));
        assertJsonEquivalent(invoice.get("fee_rollup"), source.get("fee_lines"));
        HttpResponse<String> read = get("/invoices/" + invoice.get("id").longValue());
        assertThat(read.statusCode()).isEqualTo(200);
        assertJsonEquivalent(json(read), invoice);
        assertThat(count("invoices")).isEqualTo(1);
        assertThat(count("invoice_line_snapshots")).isEqualTo(1);
    }

    @Test
    void tripInvoiceMatchesExpectedFixtureAndDoesNotDoubleCountCharges() throws Exception {
        List<JsonNode> sources = seedTrip();
        HttpResponse<String> response = post("/invoices", invoiceBody(sources));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode invoice = json(response);
        JsonNode expected = mapper.readTree(Files.readString(Path.of("fixtures/task-b/expected-invoice.json")));
        assertThat(invoice.get("currency")).isEqualTo(expected.get("currency"));
        assertThat(invoice.get("grand_total").decimalValue()).isEqualByComparingTo("685.70")
                .isEqualByComparingTo(expected.at("/totals/grand_total").decimalValue());
        assertThat(invoice.get("lines").size()).isEqualTo(5);
        for (int i = 0; i < sources.size(); i++) {
            assertSnapshot(invoice.get("lines").get(i), sources.get(i));
        }
        assertJsonEquivalent(invoice.get("tax_rollup"), expected.get("tax_rollup_example"));
        // Rollups have deterministic alphabetical ordering rather than fixture example order.
        JsonNode fees = invoice.get("fee_rollup");
        assertJsonEquivalent(fees.get(0), expected.at("/fee_rollup_example/1"));
        assertJsonEquivalent(fees.get(1), expected.at("/fee_rollup_example/0"));
        assertJsonEquivalent(json(get("/invoices/" + invoice.get("id").longValue())), invoice);
        assertThat(count("invoices")).isEqualTo(1);
        assertThat(count("invoice_line_snapshots")).isEqualTo(5);
    }

    @Test
    void taxRollupsGroupByNameAndNumericRateAndKeepNullDistinctFromZero() throws Exception {
        ObjectNode first = payload("flight");
        first.putArray("tax_lines")
                .addObject().put("name", "VAT").put("rate", new BigDecimal("0.07")).put("amount", new BigDecimal("0.10"));
        first.withArray("tax_lines").addObject().put("name", "VAT").put("amount", new BigDecimal("0.30"));
        first.withArray("tax_lines").addObject().put("name", "VAT").put("rate", BigDecimal.ZERO).put("amount", new BigDecimal("0.40"));
        first.putArray("fee_lines").addObject().put("name", "Booking").put("amount", new BigDecimal("0.10"));
        ObjectNode second = payload("hotel");
        second.putArray("tax_lines").addObject().put("name", "VAT")
                .put("rate", new BigDecimal("0.070000")).put("amount", new BigDecimal("0.20"));
        second.withArray("tax_lines").addObject().put("name", "VAT")
                .put("rate", new BigDecimal("0.20")).put("amount", new BigDecimal("0.50"));
        second.putArray("fee_lines").addObject().put("name", "Booking").put("amount", new BigDecimal("0.20"));
        List<JsonNode> sources = List.of(createTransaction(first), createTransaction(second));
        HttpResponse<String> response = post("/invoices", invoiceBody(sources));
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode invoice = json(response);
        assertJsonEquivalent(invoice.get("tax_rollup"), mapper.readTree("""
                [{"name":"VAT","amount":0.30},
                 {"name":"VAT","rate":0,"amount":0.40},
                 {"name":"VAT","rate":0.07,"amount":0.30},
                 {"name":"VAT","rate":0.20,"amount":0.50}]
                """));
        assertJsonEquivalent(invoice.get("fee_rollup"), mapper.readTree("""
                [{"name":"Booking","amount":0.30}]
                """));
        assertThat(invoice.get("grand_total").decimalValue()).isEqualByComparingTo("551.70");
        assertJsonEquivalent(json(get("/invoices/" + invoice.get("id").longValue())), invoice);
    }

    @Test
    void sourceMutationsDoNotChangeSnapshotsOrRollups() throws Exception {
        List<JsonNode> sources = seedTrip();
        HttpResponse<String> created = post("/invoices", invoiceBody(sources));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode before = json(created);
        long sourceId = sources.getFirst().get("id").longValue();
        long invoiceId = before.get("id").longValue();
        List<String> snapshotJsonBefore = jdbc.queryForList(
                "SELECT row_to_json(s)::text FROM invoice_line_snapshots s WHERE invoice_id = ? ORDER BY id",
                String.class, invoiceId);
        jdbc.update("UPDATE transactions SET total = 999.99, metadata = ?::jsonb WHERE id = ?",
                "{\"changed\":{\"amount\":123.45}}", sourceId);
        jdbc.update("DELETE FROM transaction_taxes WHERE transaction_id = ?", sourceId);
        jdbc.update("INSERT INTO transaction_taxes (transaction_id, name, rate, amount) VALUES (?, 'Changed tax', 0.5, 50.00)", sourceId);
        jdbc.update("DELETE FROM transaction_fees WHERE transaction_id = ?", sourceId);
        jdbc.update("INSERT INTO transaction_fees (transaction_id, name, amount) VALUES (?, 'Changed fee', 75.00)", sourceId);
        JsonNode changedSource = json(get(sourceId));
        assertThat(changedSource.get("total").decimalValue()).isEqualByComparingTo("999.99");
        assertThat(changedSource.at("/metadata/changed/amount").decimalValue()).isEqualByComparingTo("123.45");
        assertThat(changedSource.at("/tax_lines/0/name").asString()).isEqualTo("Changed tax");
        assertThat(changedSource.at("/fee_lines/0/name").asString()).isEqualTo("Changed fee");
        HttpResponse<String> read = get("/invoices/" + invoiceId);
        assertThat(read.statusCode()).isEqualTo(200);
        assertJsonEquivalent(json(read), before);
        assertThat(json(read).at("/lines/0/total").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(jdbc.queryForList(
                "SELECT row_to_json(s)::text FROM invoice_line_snapshots s WHERE invoice_id = ? ORDER BY id",
                String.class, invoiceId)).isEqualTo(snapshotJsonBefore);
    }

    @Test
    void snapshotConstructorCopiesMutableJsonAndCollections() {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.putObject("nested").put("value", "original");
        List<TaxLine> taxes = new ArrayList<>(List.of(new TaxLine("Tax", null, new BigDecimal("1.20"))));
        List<FeeLine> fees = new ArrayList<>(List.of(new FeeLine("Fee", new BigDecimal("2.30"))));
        InvoiceLineSnapshot snapshot = new InvoiceLineSnapshot(1L, TransactionType.FLIGHT, "EUR",
                new BigDecimal("10.00"), metadata, taxes, fees);
        ((ObjectNode) metadata.get("nested")).put("value", "changed");
        taxes.clear();
        fees.clear();
        ((ObjectNode) snapshot.getMetadata().get("nested")).put("value", "also changed");
        assertThat(snapshot.getMetadata().at("/nested/value").asString()).isEqualTo("original");
        assertThat(snapshot.getTaxLines()).containsExactly(new TaxLine("Tax", null, new BigDecimal("1.20")));
        assertThat(snapshot.getFeeLines()).containsExactly(new FeeLine("Fee", new BigDecimal("2.30")));
    }

    @Test
    void missingSourceReturns404WithNoPartialInvoice() throws Exception {
        JsonNode source = createTransaction(payload("flight"));
        ObjectNode body = invoiceBody(List.of(source));
        body.withArray("transaction_ids").add(999999L);
        assertRejected(body, 404);
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    void mixedCurrenciesReturn409WithNoPartialInvoice() throws Exception {
        JsonNode eur = createTransaction(payload("flight"));
        ObjectNode usd = payload("hotel");
        usd.put("currency", "USD");
        assertRejected(invoiceBody(List.of(eur, createTransaction(usd))), 409);
        assertThat(count("transactions")).isEqualTo(2);
    }

    @Test
    void repeatedTransactionIdReturns409WithoutDoubleCounting() throws Exception {
        JsonNode source = createTransaction(payload("flight"));
        assertRejected(invoiceBody(List.of(source, source)), 409);
        assertThat(count("transactions")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"transaction_ids\":[]}", "{\"transaction_id\":0}",
            "{\"transaction_ids\":[null]}", "{\"transaction_ids\":[-1]}",
            "{\"transaction_id\":1,\"transaction_ids\":[1]}"})
    void invalidInvoiceBodiesReturn400(String body) throws Exception {
        assertRejected(mapper.readTree(body), 400);
    }

    @Test
    void missingInvoiceReturns404() throws Exception {
        assertThat(get("/invoices/999999").statusCode()).isEqualTo(404);
    }

    @Test
    void invoiceTotalOverflowIsRejectedBeforePersistence() throws Exception {
        ObjectNode first = payload("flight");
        first.put("total", new BigDecimal("99999999999999999.99"));
        assertRejected(invoiceBody(List.of(createTransaction(first), createTransaction(payload("hotel")))), 400);
    }

    @Test
    void snapshotInsertFailureRollsBackHeaderAndPreviouslyInsertedLines() throws Exception {
        List<JsonNode> sources = seedTrip();
        jdbc.execute("CREATE FUNCTION reject_test_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type = 'hotel' THEN RAISE EXCEPTION 'forced snapshot failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_snapshot BEFORE INSERT ON invoice_line_snapshots FOR EACH ROW EXECUTE FUNCTION reject_test_snapshot()");
        try {
            assertRejected(invoiceBody(sources), 500);
            assertThat(count("transactions")).isEqualTo(5);
            assertThat(count("transaction_taxes")).isEqualTo(2);
            assertThat(count("transaction_fees")).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_snapshot ON invoice_line_snapshots");
            jdbc.execute("DROP FUNCTION reject_test_snapshot()");
        }
    }

    @Test
    void locksSourcesInAscendingIdOrderEvenWhenRequestOrderIsReversed() throws Exception {
        JsonNode lower = createTransaction(payload("flight"));
        JsonNode higher = createTransaction(payload("hotel"));
        long lowerId = lower.get("id").longValue();
        long higherId = higher.get("id").longValue();
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT id FROM transactions WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, higherId);
                lock.executeQuery().close();
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var request = executor.submit(() -> post("/invoices", invoiceBody(List.of(higher, lower))));
                try {
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    boolean waiting = false;
                    while (System.nanoTime() < deadline) {
                        waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query LIKE '%from transactions%')", Boolean.class);
                        if (waiting) { break; }
                        Thread.sleep(20);
                    }
                    assertThat(waiting).as("HTTP request is waiting for the higher-ID source lock").isTrue();
                    assertThat(request.isDone()).isFalse();
                    // The lower-ID row must already be locked by the blocked invoice request.
                    try (Connection probe = dataSource.getConnection()) {
                        probe.setAutoCommit(false);
                        try (var lock = probe.prepareStatement("SELECT id FROM transactions WHERE id = ? FOR UPDATE NOWAIT")) {
                            lock.setLong(1, lowerId);
                            assertThatThrownBy(lock::executeQuery).isInstanceOfSatisfying(SQLException.class,
                                    error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
                        } finally {
                            probe.rollback();
                        }
                    }
                } finally {
                    blocker.commit();
                }
                HttpResponse<String> response = request.get(20, TimeUnit.SECONDS);
                assertThat(response.statusCode()).isEqualTo(201);
                assertThat(json(response).at("/lines/0/transaction_id").longValue()).isEqualTo(lowerId);
                assertThat(json(response).at("/lines/1/transaction_id").longValue()).isEqualTo(higherId);
            }
        }
    }

    @Test
    void overlappingInvoicesWithReversedIdsCompleteWithoutDeadlock() throws Exception {
        List<JsonNode> sources = List.of(createTransaction(payload("flight")), createTransaction(payload("hotel")));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return post("/invoices", invoiceBody(sources));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return post("/invoices", invoiceBody(sources.reversed()));
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(first.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(second.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        }
        assertThat(count("invoices")).isEqualTo(2);
        assertThat(count("invoice_line_snapshots")).isEqualTo(4);
    }

    private List<JsonNode> seedTrip() throws Exception {
        List<JsonNode> sources = new ArrayList<>();
        for (String name : List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee")) {
            sources.add(createTransaction(payload(name)));
        }
        return sources;
    }

    private JsonNode createTransaction(ObjectNode body) throws Exception {
        HttpResponse<String> response = post(body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json(response);
    }

    private ObjectNode invoiceBody(List<JsonNode> sources) {
        ObjectNode body = mapper.createObjectNode();
        var ids = body.putArray("transaction_ids");
        sources.forEach(source -> ids.add(source.get("id").longValue()));
        return body;
    }

    private void assertRejected(JsonNode body, int expectedStatus) throws Exception {
        HttpResponse<String> response = post("/invoices", body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expectedStatus);
        assertThat(count("invoices")).isZero();
        assertThat(count("invoice_line_snapshots")).isZero();
    }

    private void assertSnapshot(JsonNode snapshot, JsonNode source) {
        assertThat(snapshot.get("transaction_id")).isEqualTo(source.get("id"));
        for (String field : List.of("type", "currency", "total", "metadata", "tax_lines", "fee_lines")) {
            assertJsonEquivalent(snapshot.get(field), source.get(field));
        }
    }
}
