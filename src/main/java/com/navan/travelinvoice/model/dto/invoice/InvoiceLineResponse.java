package com.navan.travelinvoice.model.dto.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travelinvoice.model.dto.transaction.FeeLine;
import com.navan.travelinvoice.model.dto.transaction.TaxLine;
import com.navan.travelinvoice.model.entity.invoice.InvoiceLineSnapshot;
import com.navan.travelinvoice.model.entity.transaction.TransactionType;
import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record InvoiceLineResponse(
        @JsonProperty("transaction_id") Long transactionId,
        TransactionType type, String currency, BigDecimal total,
        @JsonProperty("tax_lines") List<TaxLine> taxLines,
        @JsonProperty("fee_lines") List<FeeLine> feeLines,
        JsonNode metadata) {
    public static InvoiceLineResponse from(InvoiceLineSnapshot line) {
        return new InvoiceLineResponse(line.getTransactionId(), line.getType(), line.getCurrency(),
                line.getTotal(), line.getTaxLines(), line.getFeeLines(), line.getMetadata());
    }
}
