package com.navan.travelinvoice.model.dto.invoice;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travelinvoice.model.dto.transaction.FeeLine;
import com.navan.travelinvoice.model.dto.transaction.TaxLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InvoiceResponse(Long id, String currency,
        @JsonProperty("grand_total") BigDecimal grandTotal,
        @JsonProperty("created_at") Instant createdAt,
        List<InvoiceLineResponse> lines,
        @JsonProperty("tax_rollup") List<TaxLine> taxRollup,
        @JsonProperty("fee_rollup") List<FeeLine> feeRollup) {
}
