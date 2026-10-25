package com.matrix.payments.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only audit trail entry for a payment. */
@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, updatable = false, length = 32)
    private String paymentId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false, length = 20)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", updatable = false, length = 20)
    private PaymentStatus toStatus;

    @Column(updatable = false, length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentEvent() {
    }

    public PaymentEvent(String paymentId, String eventType, PaymentStatus from, PaymentStatus to, String detail) {
        this.paymentId = paymentId;
        this.eventType = eventType;
        this.fromStatus = from;
        this.toStatus = to;
        this.detail = detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500);
        this.createdAt = Instant.now();
    }

    public String getPaymentId() { return paymentId; }
    public String getEventType() { return eventType; }
    public PaymentStatus getFromStatus() { return fromStatus; }
    public PaymentStatus getToStatus() { return toStatus; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
