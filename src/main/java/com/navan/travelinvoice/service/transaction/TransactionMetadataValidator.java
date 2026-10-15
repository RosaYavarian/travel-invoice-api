package com.navan.travelinvoice.service.transaction;

import com.navan.travelinvoice.exception.InvalidTransactionException;
import com.navan.travelinvoice.model.entity.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class TransactionMetadataValidator {

    public void validate(TransactionType type, JsonNode metadata) {
        object(metadata, "metadata");
        switch (type) {
            case FLIGHT -> flight(metadata);
            case HOTEL -> hotel(metadata);
            case RAIL -> rail(metadata);
            case NAVAN_FEE -> {
                String kind = text(metadata, "fee_kind");
                require(kind.equals("trip_fee") || kind.equals("agent_call_fee"),
                        "metadata.fee_kind must be trip_fee or agent_call_fee");
            }
        }
    }

    private void flight(JsonNode metadata) {
        text(metadata, "origin");
        text(metadata, "destination");
        money(metadata, "fare");
        JsonNode coupons = array(metadata, "coupons");
        for (JsonNode coupon : coupons) {
            object(coupon, "coupon");
            text(coupon, "flight_number");
            text(coupon, "from");
            text(coupon, "to");
            timestamp(coupon, "departure");
            timestamp(coupon, "arrival");
            optionalText(coupon, "carrier");
        }
    }

    private void hotel(JsonNode metadata) {
        text(metadata, "name");
        JsonNode address = metadata.get("address");
        object(address, "metadata.address");
        text(address, "street");
        text(address, "city");
        text(address, "country");
        LocalDate checkIn = date(metadata, "check_in");
        LocalDate checkOut = date(metadata, "check_out");
        require(checkOut.isAfter(checkIn), "check_out must be after check_in");
        JsonNode nights = metadata.get("nights");
        require(nights != null && nights.isIntegralNumber() && nights.canConvertToInt()
                && nights.intValue() > 0, "metadata.nights must be a positive integer");
        money(metadata, "room_rate_per_night");
        if (metadata.has("extras")) {
            for (JsonNode extra : array(metadata, "extras")) {
                object(extra, "hotel extra");
                text(extra, "name");
                money(extra, "amount");
            }
        }
    }

    private void rail(JsonNode metadata) {
        JsonNode legs = array(metadata, "legs");
        require(!legs.isEmpty(), "metadata.legs must not be empty");
        for (JsonNode leg : legs) {
            object(leg, "rail leg");
            text(leg, "origin");
            text(leg, "destination");
            timestamp(leg, "departure");
            timestamp(leg, "arrival");
            optionalText(leg, "train_number");
            optionalText(leg, "class");
        }
    }

    private void money(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isNumber(), field + " must be a number");
        BigDecimal amount = value.decimalValue().stripTrailingZeros();
        require(amount.scale() <= 2 && amount.precision() - amount.scale() <= 17,
                field + " must fit a decimal with 17 integer digits and 2 fractional digits");
    }

    private void object(JsonNode value, String field) {
        require(value != null && value.isObject(), field + " must be an object");
    }

    private JsonNode array(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isArray(), field + " must be an array");
        return value;
    }

    private String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isString() && !value.asString().isBlank(),
                field + " must be a nonblank string");
        return value.asString();
    }

    private void optionalText(JsonNode parent, String field) {
        if (parent.has(field)) {
            text(parent, field);
        }
    }

    private void timestamp(JsonNode parent, String field) {
        String value = text(parent, field);
        try {
            Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidTransactionException(field + " must be an ISO-8601 timestamp with an offset");
        }
    }

    private LocalDate date(JsonNode parent, String field) {
        String value = text(parent, field);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InvalidTransactionException(field + " must be an ISO-8601 date");
        }
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new InvalidTransactionException(message);
        }
    }
}
