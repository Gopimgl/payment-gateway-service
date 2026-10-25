package com.matrix.payments.dto;

import com.matrix.payments.gateway.GatewayScenario;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record CreatePaymentRequest(
        @Schema(example = "ORD-1001")
        @NotBlank @Size(max = 64) String orderId,

        @Schema(example = "1000.00")
        @NotNull @DecimalMin(value = "0.01", message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 2) BigDecimal amount,

        @Schema(example = "INR", allowableValues = {"INR", "USD", "EUR", "GBP"})
        @NotBlank @Pattern(regexp = "INR|USD|EUR|GBP", message = "currency must be one of INR, USD, EUR, GBP") String currency,

        @Schema(description = "TEST HOOK for the mock bank (defaults to SUCCESS). Removed in production, where the bank decides.",
                example = "SUCCESS")
        GatewayScenario scenario) {
}
