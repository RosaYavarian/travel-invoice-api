package com.navan.travelinvoice.invoice;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceMutationTest extends PostgresIntegrationTestSupport {
    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Test
    void invoiceGetFetchesHeaderAndSnapshotsInOneStatement() throws Exception {
        JsonNode original = createInvoice(sources("flight", "hotel", "rail"));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            HttpResponse<String> response = get(path(original.get("id").longValue()));
            assertThat(response.statusCode()).isEqualTo(200);
            assertJsonEquivalent(json(response), original);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(previous);
        }
    }

    @Test
    void replacesTransactionSetAndRecalculatesSnapshotsTotalAndRollups() throws Exception {
        List<JsonNode> sources = sources("flight", "hotel", "rail", "trip_fee", "agent_call_fee");
        JsonNode original = createInvoice(List.of(sources.getFirst()));
        long id = original.get("id").longValue();
        HttpResponse<String> response = patch(path(id), body(sources.reversed()));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        JsonNode updated = json(response);
        assertThat(updated.get("id")).isEqualTo(original.get("id"));
        assertThat(updated.get("created_at")).isEqualTo(original.get("created_at"));
        assertThat(updated.get("currency").asString()).isEqualTo("EUR");
        assertThat(updated.get("grand_total").decimalValue()).isEqualByComparingTo("685.70");
        assertThat(updated.get("lines").size()).isEqualTo(5);
        for (int i = 0; i < sources.size(); i++) assertSnapshot(updated.get("lines").get(i), sources.get(i));
        assertJsonEquivalent(updated.get("tax_rollup"), mapper.readTree("""
                [{"name":"Airport tax","amount":15},{"name":"VAT","rate":0.07,"amount":21.70}]
                """));
        assertJsonEquivalent(updated.get("fee_rollup"), mapper.readTree("""
                [{"name":"Booking fee","amount":4},{"name":"YQ","amount":25}]
                """));
        assertJsonEquivalent(json(get(path(id))), updated);
        assertThat(count("invoice_line_snapshots")).isEqualTo(5);
    }

    @Test
    void rebuildsOverlappingIdsFromCurrentSourceAndDeletesOldRowsBeforeInsert() throws Exception {
        List<JsonNode> sources = sources("flight", "rail");
        JsonNode original = createInvoice(sources);
        long id = original.get("id").longValue();
        List<Long> oldIds = jdbc.queryForList("SELECT id FROM invoice_line_snapshots WHERE invoice_id = ?", Long.class, id);
        String ids = oldIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        long sourceId = sources.getFirst().get("id").longValue();
        jdbc.update("UPDATE transactions SET total = 123.45, metadata = '{\"source\":\"updated\",\"amount\":12345678901234567.89}'::jsonb WHERE id = ?", sourceId);
        jdbc.update("UPDATE transaction_taxes SET name = 'New tax', amount = 1.23 WHERE transaction_id = ?", sourceId);
        jdbc.update("UPDATE transaction_fees SET name = 'New fee', amount = 2.34 WHERE transaction_id = ?", sourceId);
        jdbc.execute("CREATE FUNCTION require_old_snapshot_deletion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS (SELECT 1 FROM invoice_line_snapshots WHERE id IN (" + ids + ")) THEN RAISE EXCEPTION 'old snapshots still exist'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER require_old_snapshot_deletion BEFORE INSERT ON invoice_line_snapshots FOR EACH ROW EXECUTE FUNCTION require_old_snapshot_deletion()");
        try {
            HttpResponse<String> response = patch(path(id), mapper.createObjectNode().put("transaction_id", sourceId));
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            JsonNode updated = json(response);
            assertThat(updated.get("grand_total").decimalValue()).isEqualByComparingTo("123.45");
            assertSnapshot(updated.at("/lines/0"), json(get(sourceId)));
            assertJsonEquivalent(updated.get("tax_rollup"), json(get(sourceId)).get("tax_lines"));
            assertJsonEquivalent(updated.get("fee_rollup"), json(get(sourceId)).get("fee_lines"));
            assertThat(jdbc.queryForList("SELECT id FROM invoice_line_snapshots WHERE invoice_id = ?", Long.class, id))
                    .doesNotContainAnyElementsOf(oldIds).hasSize(1);
            assertJsonEquivalent(json(get(path(id))), updated);
        } finally {
            jdbc.execute("DROP TRIGGER require_old_snapshot_deletion ON invoice_line_snapshots");
            jdbc.execute("DROP FUNCTION require_old_snapshot_deletion()");
        }
    }

    @Test
    void replacementCanChangeCurrencyWhenAllNewSourcesShareIt() throws Exception {
        JsonNode original = createInvoice(sources("flight"));
        ObjectNode usd = payload("hotel");
        usd.put("currency", "USD");
        HttpResponse<String> created = post(usd);
        assertThat(created.statusCode()).isEqualTo(201);
        HttpResponse<String> response = patch(path(original.get("id").longValue()), body(List.of(json(created))));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("currency").asString()).isEqualTo("USD");
        assertThat(json(response).get("grand_total").decimalValue()).isEqualByComparingTo("331.70");
    }

    @Test
    void missingInvoicePatchAndDeleteReturn404() throws Exception {
        assertThat(patch(path(999999), mapper.createObjectNode().put("transaction_id", 1)).statusCode()).isEqualTo(404);
        assertThat(delete(path(999999)).statusCode()).isEqualTo(404);
    }

    @Test
    void missingReplacementSourceLeavesInvoiceUnchanged() throws Exception {
        JsonNode original = createInvoice(sources("flight"));
        ObjectNode input = mapper.createObjectNode();
        input.putArray("transaction_ids").add(original.at("/lines/0/transaction_id").longValue()).add(999999L);
        assertRejected(original, input, 404);
    }

    @Test
    void mixedCurrenciesAndDuplicateIdsLeaveInvoiceUnchanged() throws Exception {
        List<JsonNode> sources = sources("flight");
        JsonNode original = createInvoice(sources);
        ObjectNode usd = payload("hotel");
        usd.put("currency", "USD");
        HttpResponse<String> created = post(usd);
        assertThat(created.statusCode()).isEqualTo(201);
        assertRejected(original, body(List.of(sources.getFirst(), json(created))), 409);
        assertRejected(original, body(List.of(sources.getFirst(), sources.getFirst())), 409);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"transaction_ids\":[]}", "{\"transaction_id\":null}",
            "{\"transaction_id\":0}", "{\"transaction_ids\":[null]}",
            "{\"transaction_id\":1,\"transaction_ids\":[1]}"})
    void invalidReplacementBodyLeavesInvoiceUnchanged(String input) throws Exception {
        assertRejected(createInvoice(sources("flight")), mapper.readTree(input), 400);
    }

    @Test
    void failedReplacementInsertRestoresOriginalHeaderAndExactSnapshotRows() throws Exception {
        List<JsonNode> sources = sources("flight", "rail", "hotel");
        JsonNode original = createInvoice(sources.subList(0, 2));
        jdbc.execute("CREATE FUNCTION fail_invoice_replacement() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.type = 'hotel' THEN RAISE EXCEPTION 'forced replacement failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_invoice_replacement BEFORE INSERT ON invoice_line_snapshots FOR EACH ROW EXECUTE FUNCTION fail_invoice_replacement()");
        try {
            assertRejected(original, body(List.of(sources.get(0), sources.get(2))), 500);
            assertThat(count("transactions")).isEqualTo(3);
        } finally {
            jdbc.execute("DROP TRIGGER fail_invoice_replacement ON invoice_line_snapshots");
            jdbc.execute("DROP FUNCTION fail_invoice_replacement()");
        }
    }

    @Test
    void failedHeaderUpdateRestoresDeletedSnapshots() throws Exception {
        List<JsonNode> sources = sources("flight", "hotel");
        JsonNode original = createInvoice(List.of(sources.getFirst()));
        jdbc.execute("CREATE FUNCTION fail_invoice_header_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced header failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_invoice_header_update BEFORE UPDATE ON invoices FOR EACH ROW EXECUTE FUNCTION fail_invoice_header_update()");
        try {
            assertRejected(original, body(List.of(sources.getLast())), 500);
        } finally {
            jdbc.execute("DROP TRIGGER fail_invoice_header_update ON invoices");
            jdbc.execute("DROP FUNCTION fail_invoice_header_update()");
        }
    }

    @Test
    void deletePreservesAllSourcesAndAllowsSubsequentMutation() throws Exception {
        List<JsonNode> sources = sources("flight", "hotel");
        JsonNode invoice = createInvoice(sources);
        long id = invoice.get("id").longValue();
        HttpResponse<String> response = delete(path(id));
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(get(path(id)).statusCode()).isEqualTo(404);
        assertThat(count("invoices")).isZero();
        assertThat(count("invoice_line_snapshots")).isZero();
        for (JsonNode source : sources) assertJsonEquivalent(json(get(source.get("id").longValue())), source);
        assertThat(count("transaction_taxes")).isEqualTo(2);
        assertThat(count("transaction_fees")).isEqualTo(1);
        assertThat(patch(sources.getFirst().get("id").longValue(), mapper.createObjectNode().put("total", 123)).statusCode()).isEqualTo(200);
        assertThat(delete(sources.getLast().get("id").longValue()).statusCode()).isEqualTo(204);
    }

    @Test
    void replacementReleasesOnlySourcesNotReferencedByAnotherInvoice() throws Exception {
        List<JsonNode> sources = sources("flight", "hotel", "rail", "trip_fee");
        JsonNode first = createInvoice(sources.subList(0, 3));
        JsonNode second = createInvoice(List.of(sources.get(0)));
        assertThat(patch(path(first.get("id").longValue()), body(List.of(sources.get(3)))).statusCode()).isEqualTo(200);
        assertThat(patch(sources.get(1).get("id").longValue(), mapper.createObjectNode().put("total", 123)).statusCode()).isEqualTo(200);
        assertThat(delete(sources.get(2).get("id").longValue()).statusCode()).isEqualTo(204);
        long shared = sources.get(0).get("id").longValue();
        assertThat(patch(shared, mapper.createObjectNode().put("total", 123)).statusCode()).isEqualTo(409);
        assertThat(delete(shared).statusCode()).isEqualTo(409);
        assertThat(delete(path(second.get("id").longValue())).statusCode()).isEqualTo(204);
        assertThat(patch(shared, mapper.createObjectNode().put("total", 123)).statusCode()).isEqualTo(200);
        assertThat(delete(shared).statusCode()).isEqualTo(204);
    }

    @Test
    void failedInvoiceDeleteRestoresHeaderAndSnapshots() throws Exception {
        JsonNode original = createInvoice(sources("flight", "hotel"));
        List<String> rows = invoiceRows();
        jdbc.execute("CREATE FUNCTION fail_invoice_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced invoice delete failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_invoice_delete BEFORE DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION fail_invoice_delete()");
        try {
            assertThat(delete(path(original.get("id").longValue())).statusCode()).isEqualTo(500);
            assertJsonEquivalent(json(get(path(original.get("id").longValue()))), original);
            assertThat(invoiceRows()).isEqualTo(rows);
            assertThat(delete(original.at("/lines/0/transaction_id").longValue()).statusCode()).isEqualTo(409);
        } finally {
            jdbc.execute("DROP TRIGGER fail_invoice_delete ON invoices");
            jdbc.execute("DROP FUNCTION fail_invoice_delete()");
        }
    }

    private List<JsonNode> sources(String... names) throws Exception {
        List<JsonNode> sources = new ArrayList<>();
        for (String name : names) {
            HttpResponse<String> response = post(payload(name));
            assertThat(response.statusCode()).isEqualTo(201);
            sources.add(json(response));
        }
        return sources;
    }

    private JsonNode createInvoice(List<JsonNode> sources) throws Exception {
        HttpResponse<String> response = post("/invoices", body(sources));
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return json(response);
    }

    private ObjectNode body(List<JsonNode> sources) {
        ObjectNode body = mapper.createObjectNode();
        var ids = body.putArray("transaction_ids");
        sources.forEach(source -> ids.add(source.get("id").longValue()));
        return body;
    }

    private String path(long id) { return "/invoices/" + id; }

    private void assertSnapshot(JsonNode actual, JsonNode source) {
        assertThat(actual.get("transaction_id")).isEqualTo(source.get("id"));
        for (String field : List.of("type", "currency", "total", "metadata", "tax_lines", "fee_lines")) {
            assertJsonEquivalent(actual.get(field), source.get(field));
        }
    }

    private void assertRejected(JsonNode original, JsonNode body, int status) throws Exception {
        List<String> rows = invoiceRows();
        HttpResponse<String> response = patch(path(original.get("id").longValue()), body);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        assertJsonEquivalent(json(get(path(original.get("id").longValue()))), original);
        assertThat(invoiceRows()).isEqualTo(rows);
    }

    private List<String> invoiceRows() {
        List<String> rows = new ArrayList<>();
        for (String table : List.of("invoices", "invoice_line_snapshots")) {
            rows.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id", String.class));
        }
        return rows;
    }
}
