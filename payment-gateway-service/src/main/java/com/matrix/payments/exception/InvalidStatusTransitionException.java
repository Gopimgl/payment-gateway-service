package com.matrix.payments.exception;

import com.matrix.payments.domain.PaymentStatus;

public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(String paymentId, PaymentStatus from, PaymentStatus to) {
        super("Payment " + paymentId + " cannot move from " + from + " to " + to);
    }
}
