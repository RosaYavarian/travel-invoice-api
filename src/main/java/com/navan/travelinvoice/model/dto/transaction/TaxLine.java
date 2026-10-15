package com.navan.travelinvoice.model.dto.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaxLine(
        @NotBlank @Size(max = 255) String name,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 4, fraction = 6) BigDecimal rate,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
