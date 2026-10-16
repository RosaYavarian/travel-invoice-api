package com.navan.travelinvoice.invoice;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceIdValidationTest extends PostgresIntegrationTestSupport {
    private long invoiceId;
    private long flightId;
    private long hotelId;
    private JsonNode original;

    @BeforeEach
    void createSources() throws Exception {
        var flight = post(payload("flight"));
        var hotel = post(payload("hotel"));
        assertThat(flight.statusCode()).isEqualTo(201);
        assertThat(hotel.statusCode()).isEqualTo(201);
        flightId = json(flight).get("id").longValue();
        hotelId = json(hotel).get("id").longValue();
        var invoice = post("/invoices", mapper.createObjectNode().put("transaction_id", flightId));
        assertThat(invoice.statusCode()).isEqualTo(201);
        original = json(invoice);
        invoiceId = original.get("id").longValue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "1.0", "1e0", "1e400", "9223372036854775808", "true", "false", "{}", "[]", "\"1.9\"", "\"abc\"", "null"})
    void invalidIdTokensAreRejectedForPostAndPatchInBothShapes(String token) throws Exception {
        for (String method : java.util.List.of("POST", "PATCH")) {
            for (String body : java.util.List.of("{\"transaction_id\":" + token + "}",
                    "{\"transaction_ids\":[" + token + "]}")) {
                var response = raw(method, body);
                assertThat(response.statusCode()).as("%s %s -> %s", method, body, response.body()).isEqualTo(400);
                assertThat(count("invoices")).isEqualTo(1);
                assertThat(count("invoice_line_snapshots")).isEqualTo(1);
                assertJsonEquivalent(json(get("/invoices/" + invoiceId)), original);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"POST,single", "POST,array", "PATCH,single", "PATCH,array"})
    void validIntegerIdsArePreserved(String method, String shape) throws Exception {
        String body = shape.equals("single") ? "{\"transaction_id\":" + hotelId + "}"
                : "{\"transaction_ids\":[" + flightId + "," + hotelId + "]}";
        var response = raw(method, body);
        assertThat(response.statusCode()).isEqualTo(method.equals("POST") ? 201 : 200);
        JsonNode invoice = json(response);
        assertThat(invoice.get("lines").size()).isEqualTo(shape.equals("single") ? 1 : 2);
        assertThat(invoice.get("grand_total").decimalValue()).isEqualByComparingTo(shape.equals("single") ? "331.70" : "551.70");
        assertThat(invoice.get("lines").get(shape.equals("single") ? 0 : 1).get("transaction_id").longValue()).isEqualTo(hotelId);
    }

    @ParameterizedTest
    @CsvSource({"POST,single", "POST,array", "PATCH,single", "PATCH,array"})
    void existingIntegerStringSupportIsPreserved(String method, String shape) throws Exception {
        String body = shape.equals("single") ? "{\"transaction_id\":\"" + hotelId + "\"}"
                : "{\"transaction_ids\":[\"" + hotelId + "\"]}";
        var response = raw(method, body);
        assertThat(response.statusCode()).isEqualTo(method.equals("POST") ? 201 : 200);
        assertThat(json(response).at("/lines/0/transaction_id").longValue()).isEqualTo(hotelId);
    }

    private HttpResponse<String> raw(String method, String body) throws Exception {
        // Preserve lexical numeric types (1.0 and 1e0); do not round-trip through a JsonNode.
        return client.send(HttpRequest.newBuilder(uri("/invoices" + (method.equals("PATCH") ? "/" + invoiceId : "")))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
