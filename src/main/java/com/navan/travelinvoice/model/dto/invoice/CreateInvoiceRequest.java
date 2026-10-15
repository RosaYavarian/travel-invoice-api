package com.navan.travelinvoice.model.dto.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travelinvoice.exception.invoice.InvalidInvoiceException;
import com.navan.travelinvoice.exception.invoice.InvoiceConflictException;
import java.util.HashSet;
import java.util.List;

public record CreateInvoiceRequest(
        @JsonProperty("transaction_id") Long transactionId,
        @JsonProperty("transaction_ids") List<Long> transactionIds) {

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
}
