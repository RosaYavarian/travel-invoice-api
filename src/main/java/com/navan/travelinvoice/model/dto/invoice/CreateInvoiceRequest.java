package com.navan.travelinvoice.model.dto.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travelinvoice.exception.invoice.InvalidInvoiceException;
import com.navan.travelinvoice.exception.invoice.InvoiceConflictException;
import java.util.HashSet;
import java.util.List;
import java.math.BigInteger;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateInvoiceRequest(
        @JsonProperty("transaction_id") @JsonDeserialize(using = IdDeserializer.class) Long transactionId,
        @JsonProperty("transaction_ids") @JsonDeserialize(contentUsing = IdDeserializer.class) List<Long> transactionIds) {

    public List<Long> sortedTransactionIds() {
        if ((transactionId == null) == (transactionIds == null)) {
            throw new InvalidInvoiceException("Provide exactly one of transaction_id or transaction_ids");
        }
        List<Long> ids = transactionId == null ? transactionIds : List.of(transactionId);
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new InvalidInvoiceException("Provide at least one positive transaction ID");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new InvoiceConflictException("A transaction ID must not occur more than once");
        }
        return ids.stream().sorted().toList();
    }

    /** Scoped to invoice identity fields; other JSON numbers/coercions are unchanged. */
    public static final class IdDeserializer extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            try {
                if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                    return parser.getBigIntegerValue().longValueExact();
                }
                // Preserve existing integer-string clients, but never accept decimal strings.
                if (parser.currentToken() == JsonToken.VALUE_STRING
                        && parser.getString().matches("[+-]?[0-9]+")) {
                    return new BigInteger(parser.getString()).longValueExact();
                }
            } catch (ArithmeticException exception) {
                throw new InvalidInvoiceException("Invoice transaction ID exceeds the integer range");
            }
            throw new InvalidInvoiceException("Invoice transaction IDs must be integers");
        }
    }
}
