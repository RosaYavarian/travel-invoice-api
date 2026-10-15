package com.navan.travelinvoice.model.entity.transaction;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

public enum TransactionType {
    FLIGHT("flight"), HOTEL("hotel"), RAIL("rail"), NAVAN_FEE("navan_fee");

    private final String value;

    TransactionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static TransactionType fromValue(String value) {
        for (TransactionType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported transaction type: " + value);
    }

    @Converter
    public static class DatabaseConverter implements AttributeConverter<TransactionType, String> {
        @Override
        public String convertToDatabaseColumn(TransactionType type) {
            return type == null ? null : type.value;
        }

        @Override
        public TransactionType convertToEntityAttribute(String value) {
            return value == null ? null : fromValue(value);
        }
    }
}
