package com.matrix.payments.service;

import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentEvent;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.repository.PaymentEventRepository;
import com.matrix.payments.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Single place where a payment changes state: enforces the state machine, audits and counts. */
@Component
public class PaymentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycle.class);

    private final PaymentEventRepository events;
    private final PaymentRepository payments;
    private final MeterRegistry meters;

    public PaymentLifecycle(PaymentEventRepository events, PaymentRepository payments, MeterRegistry meters) {
        this.events = events;
        this.payments = payments;
        this.meters = meters;
    }

    public void transition(Payment payment, PaymentStatus to, String gatewayRef, String reason,
                           String eventType, String detail) {
        PaymentStatus from = payment.getStatus();
        payment.transitionTo(to, gatewayRef, reason);
        payments.save(payment);
        audit(payment.getPaymentId(), eventType, from, to, detail);
        meters.counter("payments.transitions", "to", to.name()).increment();
        log.info("Payment {} {} -> {} ({})", payment.getPaymentId(), from, to, eventType);
    }

    public void audit(String paymentId, String eventType, PaymentStatus from, PaymentStatus to, String detail) {
        events.save(new PaymentEvent(paymentId, eventType, from, to, detail));
    }
}
