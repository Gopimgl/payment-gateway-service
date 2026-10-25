package com.matrix.payments.service;

import com.matrix.payments.config.GatewayProperties;
import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.dto.CreatePaymentRequest;
import com.matrix.payments.dto.PageResponse;
import com.matrix.payments.dto.PaymentEventResponse;
import com.matrix.payments.dto.PaymentResponse;
import com.matrix.payments.exception.IdempotencyConflictException;
import com.matrix.payments.exception.PaymentNotFoundException;
import com.matrix.payments.gateway.GatewayChargeRequest;
import com.matrix.payments.gateway.GatewayResult;
import com.matrix.payments.gateway.GatewayScenario;
import com.matrix.payments.gateway.PaymentGateway;
import com.matrix.payments.repository.PaymentEventRepository;
import com.matrix.payments.repository.PaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates a payment: persist PENDING -> call gateway (outside any DB transaction) ->
 * record the outcome. The payment row is committed before the gateway call, so a gateway
 * failure or timeout can never lose the record.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public record CreateResult(PaymentResponse payment, boolean replayed) {
    }

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentLifecycle lifecycle;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final GatewayProperties props;

    public PaymentService(PaymentRepository payments, PaymentEventRepository events, PaymentLifecycle lifecycle,
                          PaymentGateway gateway, PlatformTransactionManager txManager, GatewayProperties props) {
        this.payments = payments;
        this.events = events;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
    }

    public CreateResult create(String idempotencyKey, CreatePaymentRequest request) {
        String fingerprint = fingerprint(request);

        Optional<Payment> existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        final Payment created;
        try {
            created = tx.execute(status -> insertPending(idempotencyKey, fingerprint, request));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request carrying the same key.
            Payment winner = payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replay(winner, fingerprint);
        }
        Objects.requireNonNull(created);

        GatewayResult result = gateway.charge(new GatewayChargeRequest(
                created.getPaymentId(), created.getAmount(), created.getCurrency(),
                scenarioOf(request), props.callbackUrl()));

        Payment updated = tx.execute(status -> applyGatewayResult(created.getPaymentId(), result));
        return new CreateResult(PaymentResponse.from(Objects.requireNonNull(updated)), false);
    }

    public PaymentResponse get(String paymentId) {
        return payments.findByPaymentId(paymentId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    public PageResponse<PaymentResponse> list(PaymentStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Payment> result = status == null ? payments.findAll(pageable) : payments.findByStatus(status, pageable);
        return PageResponse.from(result.map(PaymentResponse::from));
    }

    public List<PaymentEventResponse> history(String paymentId) {
        if (payments.findByPaymentId(paymentId).isEmpty()) {
            throw new PaymentNotFoundException(paymentId);
        }
        return events.findByPaymentIdOrderByIdAsc(paymentId).stream().map(PaymentEventResponse::from).toList();
    }

    // ---------------------------------------------------------------- internals

    private Payment insertPending(String key, String fingerprint, CreatePaymentRequest request) {
        Payment payment = Payment.create(newPaymentId(), key, fingerprint, request.orderId(),
                request.amount().setScale(2, RoundingMode.HALF_UP), request.currency());
        Payment saved = payments.saveAndFlush(payment);
        lifecycle.audit(saved.getPaymentId(), "PAYMENT_CREATED", null, PaymentStatus.PENDING,
                "orderId=" + saved.getOrderId() + ", scenario=" + scenarioOf(request));
        log.info("Payment {} created for order {} ({} {})", saved.getPaymentId(), saved.getOrderId(),
                saved.getAmount(), saved.getCurrency());
        return saved;
    }

    private Payment applyGatewayResult(String paymentId, GatewayResult result) {
        Payment payment = payments.findByPaymentIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            // A callback already resolved this payment while we were waiting on the gateway.
            log.info("Payment {} already {}; ignoring gateway response {}", paymentId, payment.getStatus(),
                    result.outcome());
            return payment;
        }

        switch (result.outcome()) {
            case APPROVED -> lifecycle.transition(payment, PaymentStatus.SUCCESS, result.gatewayReference(), null,
                    "GATEWAY_APPROVED", "Synchronous approval");
            case DECLINED -> lifecycle.transition(payment, PaymentStatus.FAILED, result.gatewayReference(),
                    result.reason(), "GATEWAY_DECLINED", result.reason());
            case UNAVAILABLE -> lifecycle.transition(payment, PaymentStatus.FAILED, null,
                    result.reason(), "GATEWAY_UNAVAILABLE", result.reason());
            case UNKNOWN -> lifecycle.transition(payment, PaymentStatus.UNKNOWN, null,
                    result.reason(), "GATEWAY_TIMEOUT", result.reason());
            case ACCEPTED -> {
                payment.recordGatewayReference(result.gatewayReference());
                payments.save(payment);
                lifecycle.audit(paymentId, "GATEWAY_ACCEPTED", PaymentStatus.PENDING, PaymentStatus.PENDING,
                        "Awaiting callback, ref=" + result.gatewayReference());
            }
        }
        return payment;
    }

    private CreateResult replay(Payment existing, String fingerprint) {
        if (!existing.getRequestHash().equals(fingerprint)) {
            throw new IdempotencyConflictException(existing.getIdempotencyKey());
        }
        log.info("Idempotent replay for payment {}", existing.getPaymentId());
        return new CreateResult(PaymentResponse.from(existing), true);
    }

    private static GatewayScenario scenarioOf(CreatePaymentRequest r) {
        return r.scenario() == null ? GatewayScenario.SUCCESS : r.scenario();
    }

    private static String newPaymentId() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** Hash of the business fields; detects the same Idempotency-Key reused with a different payload. */
    public static String fingerprint(CreatePaymentRequest r) {
        BigDecimal amount = r.amount().setScale(2, RoundingMode.HALF_UP);
        String raw = r.orderId() + "|" + amount.toPlainString() + "|" + r.currency() + "|" + scenarioOf(r);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
