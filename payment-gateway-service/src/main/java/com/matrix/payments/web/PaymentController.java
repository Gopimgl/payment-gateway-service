package com.matrix.payments.web;

import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.dto.CreatePaymentRequest;
import com.matrix.payments.dto.PageResponse;
import com.matrix.payments.dto.PaymentEventResponse;
import com.matrix.payments.dto.PaymentResponse;
import com.matrix.payments.exception.InvalidRequestException;
import com.matrix.payments.service.PaymentService;
import com.matrix.payments.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentService service;
    private final ReconciliationService reconciliation;

    public PaymentController(PaymentService service, ReconciliationService reconciliation) {
        this.service = service;
        this.reconciliation = reconciliation;
    }

    @Operation(summary = "Create a payment",
            description = "Idempotent on the Idempotency-Key header. 201 for a new payment; 200 with "
                    + "'Idempotent-Replayed: true' when the key was already used with the same payload; "
                    + "409 when it was used with a different payload.")
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @Parameter(description = "Unique key per logical payment, e.g. order-1001-attempt-1", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        String key = idempotencyKey.trim();
        if (key.isEmpty() || key.length() > 128) {
            throw new InvalidRequestException("Idempotency-Key must be 1-128 characters");
        }
        PaymentService.CreateResult result = service.create(key, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.payment());
        }
        return ResponseEntity.created(URI.create("/api/v1/payments/" + result.payment().paymentId()))
                .body(result.payment());
    }

    @Operation(summary = "Get a payment by ID")
    @GetMapping("/{paymentId}")
    public PaymentResponse get(@PathVariable String paymentId) {
        return service.get(paymentId);
    }

    @Operation(summary = "List payments (paged, newest first), optionally filtered by status")
    @GetMapping
    public PageResponse<PaymentResponse> list(@RequestParam(required = false) PaymentStatus status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }

    @Operation(summary = "Ask the bank for the real status of a PENDING/UNKNOWN payment",
            description = "Same logic the scheduled reconciliation job runs; handy for demos.")
    @PostMapping("/{paymentId}/reconcile")
    public PaymentResponse reconcile(@PathVariable String paymentId) {
        return reconciliation.reconcileNow(paymentId);
    }

    @Operation(summary = "Audit trail of a payment's lifecycle events")
    @GetMapping("/{paymentId}/events")
    public List<PaymentEventResponse> history(@PathVariable String paymentId) {
        return service.history(paymentId);
    }
}
