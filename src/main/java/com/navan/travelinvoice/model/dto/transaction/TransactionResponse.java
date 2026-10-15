package com.navan.travelinvoice.model.dto.transaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travelinvoice.model.entity.transaction.Transaction;
import com.navan.travelinvoice.model.entity.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record TransactionResponse(
        Long id,
        TransactionType type,
        @JsonProperty("external_id") String externalId,
        String currency,
        @JsonProperty("occurred_at") Instant occurredAt,
        BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLine> taxLines,
        @JsonProperty("fee_lines") List<FeeLine> feeLines,
        JsonNode metadata,
        @JsonProperty("created_at") Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getType(),
                transaction.getExternalId(), transaction.getCurrency(), transaction.getOccurredAt(),
                transaction.getTotal(), transaction.getTaxLines().stream()
                        .map(tax -> new TaxLine(tax.getName(), tax.getRate(), tax.getAmount())).toList(),
                transaction.getFeeLines().stream()
                        .map(fee -> new FeeLine(fee.getName(), fee.getAmount())).toList(),
                transaction.getMetadata().deepCopy(), transaction.getCreatedAt());
    }
}
