package com.matrix.payments.dto;

import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String orderId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String gatewayReference,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getPaymentId(), p.getOrderId(), p.getAmount(), p.getCurrency(),
                p.getStatus(), p.getGatewayReference(), p.getFailureReason(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
