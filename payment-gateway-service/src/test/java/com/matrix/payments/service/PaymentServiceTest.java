package com.matrix.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.matrix.payments.config.GatewayProperties;
import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.dto.CreatePaymentRequest;
import com.matrix.payments.exception.IdempotencyConflictException;
import com.matrix.payments.gateway.GatewayChargeRequest;
import com.matrix.payments.gateway.GatewayResult;
import com.matrix.payments.gateway.GatewayScenario;
import org.mockito.ArgumentCaptor;
import com.matrix.payments.gateway.PaymentGateway;
import com.matrix.payments.repository.PaymentEventRepository;
import com.matrix.payments.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock PaymentRepository payments;
    @Mock PaymentEventRepository events;
    @Mock PaymentGateway gateway;

    private final AtomicReference<Payment> stored = new AtomicReference<>();
    private PaymentService service;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties("http://localhost:8080/mock-gateway",
                "http://localhost:8080/api/v1/payments/callback", "secret", Duration.ofSeconds(1),
                Duration.ofSeconds(2), 3, Duration.ofMillis(10), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        PaymentLifecycle lifecycle = new PaymentLifecycle(events, payments, new SimpleMeterRegistry());
        service = new PaymentService(payments, events, lifecycle, gateway,
                mock(PlatformTransactionManager.class), props);

        when(payments.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(payments.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(payments.findByPaymentIdForUpdate(anyString())).thenAnswer(inv -> Optional.of(stored.get()));
    }

    private static CreatePaymentRequest request(String amount) {
        return new CreatePaymentRequest("ORD-1", new BigDecimal(amount), "INR", GatewayScenario.SUCCESS);
    }

    @Test
    void approvedPaymentBecomesSuccess() {
        when(gateway.charge(any(GatewayChargeRequest.class))).thenReturn(GatewayResult.approved("GW-1"));

        var result = service.create("key-1", request("1000.00"));

        assertThat(result.replayed()).isFalse();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.payment().gatewayReference()).isEqualTo("GW-1");
        assertThat(result.payment().paymentId()).startsWith("PAY-");
    }

    @Test
    void declinedPaymentBecomesFailedWithReason() {
        when(gateway.charge(any())).thenReturn(GatewayResult.declined("Payment declined by issuer"));

        var result = service.create("key-2", request("500.00"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.payment().failureReason()).isEqualTo("Payment declined by issuer");
    }

    @Test
    void gatewayTimeoutLeavesPaymentUnknownNotFailed() {
        when(gateway.charge(any())).thenReturn(GatewayResult.unknown("Gateway timed out"));

        var result = service.create("key-3", request("250.00"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(stored.get()).isNotNull(); // record was persisted before the gateway call
    }

    @Test
    void gatewayUnavailableMarksPaymentFailed() {
        when(gateway.charge(any())).thenReturn(GatewayResult.unavailable("Gateway unavailable: HTTP 503"));

        var result = service.create("key-4", request("250.00"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void asyncAcceptanceKeepsPaymentPending() {
        when(gateway.charge(any())).thenReturn(GatewayResult.accepted("GW-9"));

        var result = service.create("key-5", request("75.00"));

        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.payment().gatewayReference()).isEqualTo("GW-9");
    }

    @Test
    void sameKeyAndPayloadReplaysWithoutCallingGateway() {
        CreatePaymentRequest req = request("1000.00");
        Payment existing = Payment.create("PAY-EXISTING", "key-6", PaymentService.fingerprint(req),
                "ORD-1", new BigDecimal("1000.00"), "INR");
        when(payments.findByIdempotencyKey("key-6")).thenReturn(Optional.of(existing));

        var result = service.create("key-6", req);

        assertThat(result.replayed()).isTrue();
        assertThat(result.payment().paymentId()).isEqualTo("PAY-EXISTING");
        verify(gateway, never()).charge(any());
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        Payment existing = Payment.create("PAY-EXISTING", "key-7",
                PaymentService.fingerprint(request("1000.00")),
                "ORD-1", new BigDecimal("1000.00"), "INR");
        when(payments.findByIdempotencyKey("key-7")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create("key-7", request("2000.00")))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(gateway, never()).charge(any());
    }

    @Test
    void scenarioFromRequestIsForwardedToBank() {
        when(gateway.charge(any(GatewayChargeRequest.class))).thenReturn(GatewayResult.declined("no"));

        service.create("key-8", new CreatePaymentRequest("ORD-1", new BigDecimal("10.00"), "INR", GatewayScenario.DECLINE));

        ArgumentCaptor<GatewayChargeRequest> captor = ArgumentCaptor.forClass(GatewayChargeRequest.class);
        verify(gateway).charge(captor.capture());
        assertThat(captor.getValue().scenario()).isEqualTo(GatewayScenario.DECLINE);
    }
}
