package com.matrix.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CallbackRequest(
        @NotBlank @Size(max = 128) String eventId,
        @NotBlank @Size(max = 32) String paymentId,
        @Size(max = 64) String gatewayReference,
        @NotNull CallbackStatus status,
        String failureReason) {
}
