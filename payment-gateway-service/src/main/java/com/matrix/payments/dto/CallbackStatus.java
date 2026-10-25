package com.matrix.payments.dto;

import com.matrix.payments.domain.PaymentStatus;

public enum CallbackStatus {
    SUCCESS, FAILED;

    public PaymentStatus toPaymentStatus() {
        return this == SUCCESS ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
    }
}
