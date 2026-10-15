package com.navan.travelinvoice.model.dto.transaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.navan.travelinvoice.model.entity.transaction.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

public record CreateTransactionRequest(
        @NotNull TransactionType type,
        @JsonProperty("external_id") @NotBlank @Size(max = 255) String externalId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @JsonProperty("occurred_at") @NotNull Instant occurredAt,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal total,
        @JsonProperty("tax_lines") @NotNull List<@NotNull @Valid TaxLine> taxLines,
        @JsonProperty("fee_lines") @NotNull List<@NotNull @Valid FeeLine> feeLines,
        @NotNull JsonNode metadata) {
}
