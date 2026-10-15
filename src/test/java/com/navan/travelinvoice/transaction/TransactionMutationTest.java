package com.navan.travelinvoice.transaction;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionMutationTest extends PostgresIntegrationTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"total", "occurred_at", "tax_lines", "fee_lines", "metadata"})
    void patchesEachFieldAndPreservesOmittedFields(String field) throws Exception {
        JsonNode before = createFlight();
        long id = before.get("id").longValue();
        ObjectNode input = mapper.createObjectNode();
        input.set(field, replacement().get(field));
        HttpResponse<String> response = patch(id, input);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        ObjectNode expected = (ObjectNode) before.deepCopy();
        expected.set(field, input.get(field));
        assertJsonEquivalent(json(response), expected);
        assertJsonEquivalent(json(get(id)), expected);
        if (!field.equals("tax_lines")) {
            assertThat(jdbc.queryForObject("SELECT id FROM transaction_taxes WHERE transaction_id = ?", Long.class, id)).isEqualTo(1L);
        }
        if (!field.equals("fee_lines")) {
            assertThat(jdbc.queryForObject("SELECT id FROM transaction_fees WHERE transaction_id = ?", Long.class, id)).isEqualTo(1L);
        }
    }

    @Test
    void multipleFieldPatchReplacesCompleteMetadataAndCollections() throws Exception {
        JsonNode before = createFlight();
        long id = before.get("id").longValue();
        ObjectNode input = replacement();
        HttpResponse<String> response = patch(id, input);
        assertThat(response.statusCode()).isEqualTo(200);
        ObjectNode expected = (ObjectNode) before.deepCopy();
        input.properties().forEach(entry -> expected.set(entry.getKey(), entry.getValue()));
        assertJsonEquivalent(json(response), expected);
        assertJsonEquivalent(json(get(id)), expected);
        assertThat(json(response).get("metadata").has("old_extra")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_taxes WHERE transaction_id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_fees WHERE transaction_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void emptyPatchPreservesEveryValueAndChildIdentity() throws Exception {
        JsonNode before = createFlight();
        List<String> rows = databaseRows();
        HttpResponse<String> response = patch(before.get("id").longValue(), mapper.createObjectNode());
        assertThat(response.statusCode()).isEqualTo(200);
        assertJsonEquivalent(json(response), before);
        assertThat(databaseRows()).isEqualTo(rows);
    }

    @ParameterizedTest
    @ValueSource(strings = {"total", "occurred_at", "tax_lines", "fee_lines", "metadata"})
    void explicitNullIsRejectedWithoutChangingAnyField(String field) throws Exception {
        JsonNode before = createFlight();
        ObjectNode input = mapper.createObjectNode().put("total", new BigDecimal("999.99"));
        input.putNull(field);
        assertRejectedWithoutChanges(before, input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"external_id", "type", "currency", "unknown", "id", "created_at"})
    void identityAndUnknownFieldsAreRejected(String field) throws Exception {
        JsonNode before = createFlight();
        assertRejectedWithoutChanges(before, mapper.createObjectNode().put(field, "forbidden"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"total\":1.001}", "{\"total\":100000000000000000}",
            "{\"occurred_at\":\"invalid\"}", "{\"tax_lines\":[null]}",
            "{\"tax_lines\":[{\"name\":\"Tax\",\"amount\":1,\"rate\":1.1}]}",
            "{\"fee_lines\":[{\"name\":\" \",\"amount\":1}]}",
            "{\"fee_lines\":{}}", "{\"metadata\":[]}", "[]", "null"})
    void invalidPatchReturns400WithoutPersistence(String input) throws Exception {
        assertRejectedWithoutChanges(createFlight(), mapper.readTree(input));
    }

    @Test
    void partialMetadataIsRejectedRatherThanMergedAndOtherFieldsAreNotApplied() throws Exception {
        JsonNode before = createFlight();
        ObjectNode input = mapper.createObjectNode().put("total", new BigDecimal("999.99"));
        input.putObject("metadata").put("origin", "BER");
        assertRejectedWithoutChanges(before, input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hotel", "rail", "trip_fee", "agent_call_fee"})
    void metadataIsValidatedAgainstStoredType(String seed) throws Exception {
        HttpResponse<String> created = post(payload(seed));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode before = json(created);
        ObjectNode input = mapper.createObjectNode();
        input.set("metadata", payload("flight").get("metadata"));
        assertRejectedWithoutChanges(before, input);
    }

    @Test
    void emptyArraysClearTaxesAndFees() throws Exception {
        long id = createFlight().get("id").longValue();
        ObjectNode input = mapper.createObjectNode();
        input.putArray("tax_lines");
        input.putArray("fee_lines");
        assertThat(patch(id, input).statusCode()).isEqualTo(200);
        assertThat(json(get(id)).get("tax_lines").isEmpty()).isTrue();
        assertThat(json(get(id)).get("fee_lines").isEmpty()).isTrue();
        assertThat(count("transaction_taxes")).isZero();
        assertThat(count("transaction_fees")).isZero();
    }

    @Test
    void missingTransactionReturns404ForPatchAndDelete() throws Exception {
        assertThat(patch(999999, mapper.createObjectNode().put("total", 1)).statusCode()).isEqualTo(404);
        assertThat(delete(999999).statusCode()).isEqualTo(404);
    }

    @Test
    void invoicedTransactionRejectsPatchAndDeleteAndPreservesInvoiceSnapshot() throws Exception {
        JsonNode before = createFlight();
        long id = before.get("id").longValue();
        HttpResponse<String> invoiceResponse = post("/invoices", mapper.createObjectNode().put("transaction_id", id));
        assertThat(invoiceResponse.statusCode()).isEqualTo(201);
        JsonNode invoice = json(invoiceResponse);
        List<String> rows = databaseRows();
        assertThat(patch(id, replacement()).statusCode()).isEqualTo(409);
        assertThat(patch(id, mapper.createObjectNode()).statusCode()).isEqualTo(409);
        assertThat(delete(id).statusCode()).isEqualTo(409);
        assertJsonEquivalent(json(get(id)), before);
        assertJsonEquivalent(json(get("/invoices/" + invoice.get("id").longValue())), invoice);
        assertThat(databaseRows()).isEqualTo(rows);
        assertThat(count("invoice_line_snapshots")).isEqualTo(1);
    }

    @Test
    void deleteRemovesUninvoicedHeaderAndChildren() throws Exception {
        long id = createFlight().get("id").longValue();
        HttpResponse<String> response = delete(id);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(get(id).statusCode()).isEqualTo(404);
        assertThat(count("transactions")).isZero();
        assertThat(count("transaction_taxes")).isZero();
        assertThat(count("transaction_fees")).isZero();
    }

    @Test
    void failedFeeReplacementRollsBackHeaderMetadataAndAllChildren() throws Exception {
        JsonNode before = createFlight();
        List<String> rows = databaseRows();
        jdbc.execute("CREATE FUNCTION reject_patch_fee() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced patch failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_patch_fee BEFORE INSERT ON transaction_fees FOR EACH ROW EXECUTE FUNCTION reject_patch_fee()");
        try {
            assertThat(patch(before.get("id").longValue(), replacement()).statusCode()).isEqualTo(500);
            assertJsonEquivalent(json(get(before.get("id").longValue())), before);
            assertThat(databaseRows()).isEqualTo(rows);
        } finally {
            jdbc.execute("DROP TRIGGER reject_patch_fee ON transaction_fees");
            jdbc.execute("DROP FUNCTION reject_patch_fee()");
        }
    }

    @Test
    void failedHeaderDeleteRollsBackChildDeletes() throws Exception {
        JsonNode before = createFlight();
        List<String> rows = databaseRows();
        jdbc.execute("CREATE FUNCTION reject_transaction_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced delete failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_transaction_delete BEFORE DELETE ON transactions FOR EACH ROW EXECUTE FUNCTION reject_transaction_delete()");
        try {
            assertThat(delete(before.get("id").longValue()).statusCode()).isEqualTo(500);
            assertJsonEquivalent(json(get(before.get("id").longValue())), before);
            assertThat(databaseRows()).isEqualTo(rows);
        } finally {
            jdbc.execute("DROP TRIGGER reject_transaction_delete ON transactions");
            jdbc.execute("DROP FUNCTION reject_transaction_delete()");
        }
    }

    private JsonNode createFlight() throws Exception {
        ObjectNode input = payload("flight");
        ((ObjectNode) input.get("metadata")).put("old_extra", "must disappear on replacement");
        HttpResponse<String> created = post(input);
        assertThat(created.statusCode()).isEqualTo(201);
        return json(created);
    }

    private ObjectNode replacement() {
        ObjectNode input = mapper.createObjectNode().put("total", new BigDecimal("345.67"))
                .put("occurred_at", "2026-04-01T10:00:00Z");
        ObjectNode metadata = (ObjectNode) payload("flight").get("metadata");
        metadata.put("origin", "BER");
        metadata.putObject("extra").put("amount", new BigDecimal("12345678901234567.89"));
        input.set("metadata", metadata);
        input.putArray("tax_lines").addObject().put("name", "Tax A").put("amount", new BigDecimal("1.23"));
        input.withArray("tax_lines").addObject().put("name", "Tax B").put("amount", new BigDecimal("2.34"));
        input.putArray("fee_lines").addObject().put("name", "Fee A").put("amount", new BigDecimal("3.45"));
        input.withArray("fee_lines").addObject().put("name", "Fee B").put("amount", new BigDecimal("4.56"));
        return input;
    }

    private void assertRejectedWithoutChanges(JsonNode before, JsonNode input) throws Exception {
        List<String> rows = databaseRows();
        HttpResponse<String> response = patch(before.get("id").longValue(), input);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
        assertJsonEquivalent(json(get(before.get("id").longValue())), before);
        assertThat(databaseRows()).isEqualTo(rows);
    }

    private List<String> databaseRows() {
        var rows = new java.util.ArrayList<String>();
        for (String table : List.of("transactions", "transaction_taxes", "transaction_fees")) {
            rows.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t ORDER BY id", String.class));
        }
        return rows;
    }
}
