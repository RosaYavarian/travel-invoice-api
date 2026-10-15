package com.navan.travelinvoice.model.dto.transaction;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record FeeLine(
        @NotBlank @Size(max = 255) String name,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
