package com.matrix.payments.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Remembers gateway event IDs so duplicate callbacks are applied at most once. */
@Entity
@Table(name = "processed_callbacks")
public class ProcessedCallback {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "payment_id", nullable = false, length = 32)
    private String paymentId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected ProcessedCallback() {
    }

    public ProcessedCallback(String eventId, String paymentId, String status) {
        this.eventId = eventId;
        this.paymentId = paymentId;
        this.status = status;
        this.receivedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
}
