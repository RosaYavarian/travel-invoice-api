package com.navan.travelinvoice.transaction;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionValidationTest extends PostgresIntegrationTestSupport {
    @Test
    void emptyCouponsAreAllowedButTheFieldIsRequired() throws Exception {
        ObjectNode input = payload("flight");
        ((ObjectNode) input.get("metadata")).putArray("coupons");
        assertThat(post(input).statusCode()).isEqualTo(201);
        input.put("external_id", "missing-coupons");
        ((ObjectNode) input.get("metadata")).remove("coupons");
        assertThat(post(input).statusCode()).isEqualTo(400);
        assertThat(count("transactions")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"type", "external_id", "currency", "occurred_at", "total", "tax_lines", "fee_lines", "metadata"})
    void requiredFieldsCannotBeMissing(String field) throws Exception {
        ObjectNode input = payload("flight");
        input.remove(field);
        assertInvalidAndEmpty(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZZ", "eur", "EU", "EURO", "123"})
    void invalidCurrencyReturns400(String currency) throws Exception {
        ObjectNode input = payload("flight");
        input.put("currency", currency);
        assertInvalidAndEmpty(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-type", "blank-id", "invalid-time", "money-scale", "money-overflow", "null-tax", "blank-tax", "tax-rate", "tax-scale", "fee-scale", "nonobject-metadata", "missing-flight-origin", "invalid-coupon", "missing-hotel-address", "missing-hotel-city", "invalid-hotel-date", "missing-hotel-rate", "empty-rail-legs", "invalid-rail-leg", "unknown-fee-kind"})
    void invalidFieldsOrMetadataReturn400WithoutPartialWrites(String scenario) throws Exception {
        String seed = scenario.contains("hotel") ? "hotel" : scenario.contains("rail") ? "rail"
                : scenario.contains("fee-kind") ? "trip_fee" : "flight";
        ObjectNode input = payload(seed);
        ObjectNode metadata = (ObjectNode) input.get("metadata");
        switch (scenario) {
            case "unknown-type" -> input.put("type", "car");
            case "blank-id" -> input.put("external_id", " ");
            case "invalid-time" -> input.put("occurred_at", "yesterday");
            case "money-scale" -> input.put("total", new BigDecimal("1.001"));
            case "money-overflow" -> input.put("total", new BigDecimal("100000000000000000.00"));
            case "null-tax" -> input.putArray("tax_lines").addNull();
            case "blank-tax" -> ((ObjectNode) input.at("/tax_lines/0")).put("name", " ");
            case "tax-rate" -> ((ObjectNode) input.at("/tax_lines/0")).put("rate", new BigDecimal("1.01"));
            case "tax-scale" -> ((ObjectNode) input.at("/tax_lines/0")).put("rate", new BigDecimal("0.1234567"));
            case "fee-scale" -> ((ObjectNode) input.at("/fee_lines/0")).put("amount", new BigDecimal("1.001"));
            case "nonobject-metadata" -> input.putArray("metadata");
            case "missing-flight-origin" -> metadata.remove("origin");
            case "invalid-coupon" -> ((ObjectNode) metadata.at("/coupons/0")).remove("flight_number");
            case "missing-hotel-address" -> metadata.remove("address");
            case "missing-hotel-city" -> ((ObjectNode) metadata.get("address")).remove("city");
            case "invalid-hotel-date" -> metadata.put("check_out", "invalid");
            case "missing-hotel-rate" -> metadata.remove("room_rate_per_night");
            case "empty-rail-legs" -> metadata.putArray("legs");
            case "invalid-rail-leg" -> ((ObjectNode) metadata.at("/legs/0")).put("departure", "not-a-time");
            case "unknown-fee-kind" -> metadata.put("fee_kind", "unsupported");
            default -> throw new AssertionError(scenario);
        }
        assertInvalidAndEmpty(input);
    }

}
