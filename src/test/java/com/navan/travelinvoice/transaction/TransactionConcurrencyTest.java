package com.navan.travelinvoice.transaction;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionConcurrencyTest extends PostgresIntegrationTestSupport {
    @RepeatedTest(value = 25, name = "concurrent duplicate POST race {currentRepetition}/{totalRepetitions}")
    void concurrentDuplicatePostsPersistExactlyOneCompleteTransaction() throws Exception {
        ObjectNode input = payload("flight");
        String externalId = input.get("external_id").asString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return post(input);
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return post(input);
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            var responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                    .withFailMessage("Unexpected responses: %s", responses.stream()
                            .map(r -> r.statusCode() + ": " + r.body()).toList())
                    .containsExactlyInAnyOrder(201, 409);
            HttpResponse<String> createdResponse = responses.stream()
                    .filter(r -> r.statusCode() == 201).findFirst().orElseThrow();
            JsonNode created = json(createdResponse);
            JsonNode conflict = json(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow());
            long id = created.get("id").longValue();
            assertThat(id).isPositive();
            assertThat(conflict.get("existing_id").longValue()).isEqualTo(id);
            assertThat(createdResponse.headers().firstValue("Location")).contains("/transactions/" + id);

            HttpResponse<String> getResponse = get(id);
            assertThat(getResponse.statusCode()).isEqualTo(200);
            JsonNode stored = json(getResponse);
            assertJsonEquivalent(stored, created);
            for (String field : List.of("type", "external_id", "currency", "occurred_at", "total",
                    "metadata", "tax_lines", "fee_lines")) {
                assertJsonEquivalent(stored.get(field), input.get(field));
            }

            assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE external_id = ?",
                    Integer.class, externalId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT id FROM transactions WHERE external_id = ?",
                    Long.class, externalId)).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT total FROM transactions WHERE external_id = ?",
                    BigDecimal.class, externalId)).isEqualByComparingTo(input.get("total").decimalValue());
            assertJsonEquivalent(mapper.readTree(jdbc.queryForObject(
                    "SELECT metadata::text FROM transactions WHERE external_id = ?", String.class, externalId)),
                    input.get("metadata"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_taxes WHERE transaction_id = ?",
                    Integer.class, id)).isEqualTo(input.get("tax_lines").size());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_fees WHERE transaction_id = ?",
                    Integer.class, id)).isEqualTo(input.get("fee_lines").size());
        }
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("transaction_taxes")).isEqualTo(input.get("tax_lines").size());
        assertThat(count("transaction_fees")).isEqualTo(input.get("fee_lines").size());
    }

}
