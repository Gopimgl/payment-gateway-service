package com.matrix.payments.domain;

import com.matrix.payments.exception.InvalidStatusTransitionException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 32)
    private String paymentId;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "order_id", nullable = false, updatable = false, length = 64)
    private String orderId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "gateway_reference", length = 64)
    private String gatewayReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public static Payment create(String paymentId, String idempotencyKey, String requestHash,
                                 String orderId, BigDecimal amount, String currency) {
        Payment p = new Payment();
        p.paymentId = paymentId;
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.orderId = orderId;
        p.amount = amount;
        p.currency = currency;
        p.status = PaymentStatus.PENDING;
        return p;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void transitionTo(PaymentStatus next, String gatewayRef, String reason) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidStatusTransitionException(paymentId, status, next);
        }
        this.status = next;
        if (gatewayRef != null) {
            this.gatewayReference = gatewayRef;
        }
        this.failureReason = (next == PaymentStatus.FAILED || next == PaymentStatus.UNKNOWN) ? truncate(reason, 255) : null;
    }

    public void recordGatewayReference(String ref) {
        this.gatewayReference = ref;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    public Long getId() { return id; }
    public String getPaymentId() { return paymentId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getGatewayReference() { return gatewayReference; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
