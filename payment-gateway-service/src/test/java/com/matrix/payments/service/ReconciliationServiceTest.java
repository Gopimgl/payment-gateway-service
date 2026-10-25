package com.matrix.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.gateway.GatewayResult;
import com.matrix.payments.gateway.PaymentGateway;
import com.matrix.payments.repository.PaymentEventRepository;
import com.matrix.payments.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
class ReconciliationServiceTest {

    @Mock PaymentRepository payments;
    @Mock PaymentEventRepository events;
    @Mock PaymentGateway gateway;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        PaymentLifecycle lifecycle = new PaymentLifecycle(events, payments, new SimpleMeterRegistry());
        service = new ReconciliationService(payments, gateway, lifecycle, mock(PlatformTransactionManager.class), 60000L);
        when(payments.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Payment payment(PaymentStatus status) {
        Payment p = Payment.create("PAY-1", "k", "h", "ORD-1", new BigDecimal("100.00"), "INR");
        if (status != PaymentStatus.PENDING) {
            p.transitionTo(status, null, "test");
        }
        when(payments.findByStatusInAndUpdatedAtBefore(any(), any())).thenReturn(List.of(p));
        when(payments.findByPaymentIdForUpdate(anyString())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void unknownPaymentApprovedByBankBecomesSuccess() {
        Payment p = payment(PaymentStatus.UNKNOWN);
        when(gateway.queryStatus("PAY-1")).thenReturn(GatewayResult.approved("GW-1"));

        assertThat(service.reconcileStale(Instant.now())).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getGatewayReference()).isEqualTo("GW-1");
    }

    @Test
    void pendingPaymentUnknownToBankBecomesFailed() {
        Payment p = payment(PaymentStatus.PENDING);
        when(gateway.queryStatus("PAY-1")).thenReturn(GatewayResult.notFound());

        assertThat(service.reconcileStale(Instant.now())).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void bankStillUnsureLeavesPaymentUntouched() {
        Payment p = payment(PaymentStatus.UNKNOWN);
        when(gateway.queryStatus("PAY-1")).thenReturn(GatewayResult.unknown("bank down"));

        assertThat(service.reconcileStale(Instant.now())).isEqualTo(0);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void paymentAlreadyResolvedByCallbackIsNotTouched() {
        Payment p = payment(PaymentStatus.SUCCESS);
        when(gateway.queryStatus("PAY-1")).thenReturn(GatewayResult.declined("late"));

        assertThat(service.reconcileStale(Instant.now())).isEqualTo(0);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }
}
