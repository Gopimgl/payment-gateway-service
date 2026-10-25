package com.matrix.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrix.payments.config.GatewayProperties;
import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.domain.ProcessedCallback;
import com.matrix.payments.dto.CallbackOutcome;
import com.matrix.payments.exception.InvalidRequestException;
import com.matrix.payments.exception.InvalidSignatureException;
import com.matrix.payments.exception.PaymentNotFoundException;
import com.matrix.payments.repository.PaymentEventRepository;
import com.matrix.payments.repository.PaymentRepository;
import com.matrix.payments.repository.ProcessedCallbackRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CallbackServiceTest {

    @Mock PaymentRepository payments;
    @Mock ProcessedCallbackRepository processed;
    @Mock PaymentEventRepository events;

    private SignatureService signatures;
    private CallbackService service;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties("http://x", "http://y", "test-secret",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 3, Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        signatures = new SignatureService(props);
        PaymentLifecycle lifecycle = new PaymentLifecycle(events, payments, new SimpleMeterRegistry());
        service = new CallbackService(payments, processed, lifecycle, signatures, new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator());
        when(payments.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(processed.existsById(anyString())).thenReturn(false);
    }

    private static Payment pendingPayment() {
        return Payment.create("PAY-1", "k", "h", "ORD-1", new BigDecimal("100.00"), "INR");
    }

    private static String body(String eventId, String status) {
        return "{\"eventId\":\"" + eventId + "\",\"paymentId\":\"PAY-1\",\"gatewayReference\":\"GW-1\",\"status\":\""
                + status + "\"}";
    }

    @Test
    void successCallbackUpdatesPendingPayment() {
        Payment p = pendingPayment();
        when(payments.findByPaymentIdForUpdate("PAY-1")).thenReturn(Optional.of(p));
        String json = body("EVT-1", "SUCCESS");

        var response = service.handle(json, signatures.sign(json));

        assertThat(response.outcome()).isEqualTo(CallbackOutcome.APPLIED);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getGatewayReference()).isEqualTo("GW-1");
        verify(processed).save(any(ProcessedCallback.class));
    }

    @Test
    void timedOutPaymentIsResolvedByLateCallback() {
        Payment p = pendingPayment();
        p.transitionTo(PaymentStatus.UNKNOWN, null, "timeout");
        when(payments.findByPaymentIdForUpdate("PAY-1")).thenReturn(Optional.of(p));
        String json = body("EVT-2", "SUCCESS");

        service.handle(json, signatures.sign(json));

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void duplicateEventIsNotAppliedTwice() {
        Payment p = pendingPayment();
        when(payments.findByPaymentIdForUpdate("PAY-1")).thenReturn(Optional.of(p));
        when(processed.existsById("EVT-1")).thenReturn(true);
        String json = body("EVT-1", "SUCCESS");

        var response = service.handle(json, signatures.sign(json));

        assertThat(response.outcome()).isEqualTo(CallbackOutcome.DUPLICATE);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(processed, never()).save(any());
    }

    @Test
    void conflictingCallbackCannotOverwriteFinalState() {
        Payment p = pendingPayment();
        p.transitionTo(PaymentStatus.SUCCESS, "GW-1", null);
        when(payments.findByPaymentIdForUpdate("PAY-1")).thenReturn(Optional.of(p));
        String json = body("EVT-3", "FAILED");

        var response = service.handle(json, signatures.sign(json));

        assertThat(response.outcome()).isEqualTo(CallbackOutcome.IGNORED);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void invalidSignatureIsRejected() {
        assertThatThrownBy(() -> service.handle(body("EVT-4", "SUCCESS"), "deadbeef"))
                .isInstanceOf(InvalidSignatureException.class);
        assertThatThrownBy(() -> service.handle(body("EVT-4", "SUCCESS"), null))
                .isInstanceOf(InvalidSignatureException.class);
    }

    @Test
    void invalidStatusIsRejected() {
        String json = body("EVT-5", "REFUNDED");
        assertThatThrownBy(() -> service.handle(json, signatures.sign(json)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void missingFieldsAreRejected() {
        String json = "{\"status\":\"SUCCESS\"}";
        assertThatThrownBy(() -> service.handle(json, signatures.sign(json)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void unknownPaymentIsNotFound() {
        when(payments.findByPaymentIdForUpdate("PAY-1")).thenReturn(Optional.empty());
        String json = body("EVT-6", "SUCCESS");
        assertThatThrownBy(() -> service.handle(json, signatures.sign(json)))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
