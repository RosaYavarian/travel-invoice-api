package com.navan.travelinvoice.transaction;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionIntegrationTest extends PostgresIntegrationTestSupport {
    @ParameterizedTest
    @ValueSource(strings = {"flight", "hotel", "rail", "trip_fee", "agent_call_fee"})
    void seedTransactionRoundTripsThroughPostgres(String seed) throws Exception {
        ObjectNode input = payload(seed);
        HttpResponse<String> created = post(input);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        long id = body.get("id").longValue();
        assertThat(id).isPositive();
        assertThat(body.get("created_at").isString()).isTrue();
        assertThat(created.headers().firstValue("Location")).contains("/transactions/" + id);
        HttpResponse<String> getResponse = get(id);
        assertThat(getResponse.statusCode()).isEqualTo(200);
        JsonNode stored = json(getResponse);
        assertThat(stored.get("type")).isEqualTo(input.get("type"));
        assertThat(stored.get("external_id")).isEqualTo(input.get("external_id"));
        assertThat(stored.get("currency")).isEqualTo(input.get("currency"));
        assertThat(stored.get("occurred_at")).isEqualTo(input.get("occurred_at"));
        assertThat(stored.get("total").decimalValue()).isEqualByComparingTo(input.get("total").decimalValue());
        assertJsonEquivalent(stored.get("metadata"), input.get("metadata"));
        assertJsonEquivalent(stored.get("tax_lines"), input.get("tax_lines"));
        assertJsonEquivalent(stored.get("fee_lines"), input.get("fee_lines"));
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM transactions WHERE id = ?", String.class, id))
                .isNotBlank();
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_taxes")).isEqualTo(input.get("tax_lines").size());
        assertThat(count("transaction_fees")).isEqualTo(input.get("fee_lines").size());
    }

    @Test
    void metadataPreservesAdditionalFieldsAndDecimalPrecision() throws Exception {
        ObjectNode input = payload("flight");
        ObjectNode metadata = (ObjectNode) input.get("metadata");
        metadata.put("fare", new BigDecimal("12345678901234567.89"));
        metadata.putObject("additional").put("amount", new BigDecimal("98765432101234567.12"))
                .put("reference", "keep me");
        JsonNode created = json(post(input));
        JsonNode stored = json(get(created.get("id").longValue()));
        assertJsonEquivalent(stored.get("metadata"), input.get("metadata"));
        assertThat(stored.at("/metadata/additional/amount").decimalValue())
                .isEqualByComparingTo("98765432101234567.12");
    }

    @Test
    void duplicateReturnsExistingIdAndDoesNotOverwriteHeaderOrChildren() throws Exception {
        JsonNode first = json(post(payload("flight")));
        ObjectNode duplicate = payload("duplicate_flight");
        duplicate.put("total", new BigDecimal("999.99"));
        HttpResponse<String> response = post(duplicate);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("existing_id").longValue()).isEqualTo(first.get("id").longValue());
        assertJsonEquivalent(json(get(first.get("id").longValue())), first);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_taxes")).isEqualTo(1);
        assertThat(count("transaction_fees")).isEqualTo(1);
    }

    @Test
    void taxAndFeeLinesAreReadInAscendingDatabaseIdOrder() throws Exception {
        long id = json(post(payload("flight"))).get("id").longValue();
        jdbc.update("INSERT INTO transaction_taxes (id, transaction_id, name, amount) VALUES (100, ?, 'Later', 1), (50, ?, 'Earlier', 2)", id, id);
        jdbc.update("INSERT INTO transaction_fees (id, transaction_id, name, amount) VALUES (100, ?, 'Later', 1), (50, ?, 'Earlier', 2)", id, id);
        JsonNode stored = json(get(id));
        assertThat(stored.get("tax_lines").valueStream().map(n -> n.get("name").asString()).toList())
                .containsExactly("Airport tax", "Earlier", "Later");
        assertThat(stored.get("fee_lines").valueStream().map(n -> n.get("name").asString()).toList())
                .containsExactly("YQ", "Earlier", "Later");
    }

    @Test
    void missingTransactionReturns404AndMalformedIdReturns400() throws Exception {
        assertThat(get(999999).statusCode()).isEqualTo(404);
        assertThat(client.send(HttpRequest.newBuilder(uri("/transactions/abc")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }

    @Test
    void databaseFailureDuringFeeInsertRollsBackHeaderAndTaxes() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_test_fee() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced test failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_test_fee BEFORE INSERT ON transaction_fees FOR EACH ROW EXECUTE FUNCTION reject_test_fee()");
        try {
            assertThat(post(payload("flight")).statusCode()).isEqualTo(500);
            assertThat(count("transactions")).isZero();
            assertThat(count("transaction_taxes")).isZero();
            assertThat(count("transaction_fees")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_fee ON transaction_fees");
            jdbc.execute("DROP FUNCTION reject_test_fee()");
        }
    }

}
