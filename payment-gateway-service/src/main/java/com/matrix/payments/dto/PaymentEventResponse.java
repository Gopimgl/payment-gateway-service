package com.matrix.payments.dto;

import com.matrix.payments.domain.PaymentEvent;
import com.matrix.payments.domain.PaymentStatus;
import java.time.Instant;

public record PaymentEventResponse(String eventType, PaymentStatus fromStatus, PaymentStatus toStatus,
                                   String detail, Instant createdAt) {
    public static PaymentEventResponse from(PaymentEvent e) {
        return new PaymentEventResponse(e.getEventType(), e.getFromStatus(), e.getToStatus(), e.getDetail(), e.getCreatedAt());
    }
}
