package com.matrix.payments.dto;

import com.matrix.payments.domain.PaymentStatus;

public record CallbackResponse(String paymentId, CallbackOutcome outcome, PaymentStatus status) {
}
