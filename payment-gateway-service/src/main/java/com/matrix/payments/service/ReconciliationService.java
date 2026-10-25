package com.matrix.payments.service;

import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.dto.PaymentResponse;
import com.matrix.payments.exception.PaymentNotFoundException;
import com.matrix.payments.gateway.GatewayResult;
import com.matrix.payments.gateway.PaymentGateway;
import com.matrix.payments.repository.PaymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Safety net for payments whose outcome never arrived (lost callback, bank timeout, crash between
 * "saved PENDING" and "called bank"). Periodically asks the bank for the real status.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentLifecycle lifecycle;
    private final TransactionTemplate tx;
    private final Duration staleAfter;

    public ReconciliationService(PaymentRepository payments, PaymentGateway gateway, PaymentLifecycle lifecycle,
                                 PlatformTransactionManager txManager,
                                 @Value("${payment.reconciliation.stale-after-ms:60000}") long staleAfterMs) {
        this.payments = payments;
        this.gateway = gateway;
        this.lifecycle = lifecycle;
        this.tx = new TransactionTemplate(txManager);
        this.staleAfter = Duration.ofMillis(staleAfterMs);
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval-ms:30000}",
            initialDelayString = "${payment.reconciliation.interval-ms:30000}")
    public void scheduledRun() {
        int resolved = reconcileStale(Instant.now());
        if (resolved > 0) {
            log.info("Reconciliation resolved {} payment(s)", resolved);
        }
    }

    /** Checks every PENDING/UNKNOWN payment older than the stale threshold. Returns how many were resolved. */
    public int reconcileStale(Instant now) {
        List<Payment> stale = payments.findByStatusInAndUpdatedAtBefore(
                List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN), now.minus(staleAfter));
        int resolved = 0;
        for (Payment p : stale) {
            try {
                if (reconcileOne(p.getPaymentId())) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.warn("Reconciliation failed for {}: {}", p.getPaymentId(), e.getMessage());
            }
        }
        return resolved;
    }

    /** On-demand reconciliation of a single payment (also exposed over HTTP). */
    public PaymentResponse reconcileNow(String paymentId) {
        Payment p = payments.findByPaymentId(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!p.getStatus().isTerminal()) {
            reconcileOne(paymentId);
        }
        return PaymentResponse.from(payments.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId)));
    }

    private boolean reconcileOne(String paymentId) {
        GatewayResult bank = gateway.queryStatus(paymentId); // network call outside any DB transaction
        return Boolean.TRUE.equals(tx.execute(status -> apply(paymentId, bank)));
    }

    private boolean apply(String paymentId, GatewayResult bank) {
        Payment p = payments.findByPaymentIdForUpdate(paymentId).orElse(null);
        if (p == null || p.getStatus().isTerminal()) {
            return false; // a callback got there first
        }
        switch (bank.outcome()) {
            case APPROVED -> lifecycle.transition(p, PaymentStatus.SUCCESS, bank.gatewayReference(), null,
                    "RECONCILED_SUCCESS", "Confirmed by bank status query");
            case DECLINED -> lifecycle.transition(p, PaymentStatus.FAILED, bank.gatewayReference(), bank.reason(),
                    "RECONCILED_FAILED", "Declined according to bank status query");
            case NOT_FOUND -> lifecycle.transition(p, PaymentStatus.FAILED, null, bank.reason(),
                    "RECONCILED_FAILED", "Bank never received this payment");
            default -> {
                lifecycle.audit(paymentId, "RECONCILE_STILL_OPEN", p.getStatus(), p.getStatus(),
                        "Bank answer: " + bank.outcome());
                return false;
            }
        }
        return true;
    }
}
