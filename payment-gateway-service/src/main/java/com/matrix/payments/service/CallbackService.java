package com.matrix.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.domain.ProcessedCallback;
import com.matrix.payments.dto.CallbackOutcome;
import com.matrix.payments.dto.CallbackRequest;
import com.matrix.payments.dto.CallbackResponse;
import com.matrix.payments.exception.InvalidRequestException;
import com.matrix.payments.exception.PaymentNotFoundException;
import com.matrix.payments.repository.PaymentRepository;
import com.matrix.payments.repository.ProcessedCallbackRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles gateway webhooks. Idempotent: an event ID is applied at most once, and a final state
 * (SUCCESS/FAILED) can never be overwritten by a later or conflicting callback.
 */
@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final PaymentRepository payments;
    private final ProcessedCallbackRepository processed;
    private final PaymentLifecycle lifecycle;
    private final SignatureService signatures;
    private final ObjectMapper mapper;
    private final Validator validator;

    public CallbackService(PaymentRepository payments, ProcessedCallbackRepository processed,
                           PaymentLifecycle lifecycle, SignatureService signatures,
                           ObjectMapper mapper, Validator validator) {
        this.payments = payments;
        this.processed = processed;
        this.lifecycle = lifecycle;
        this.signatures = signatures;
        this.mapper = mapper;
        this.validator = validator;
    }

    @Transactional
    public CallbackResponse handle(String rawBody, String signature) {
        signatures.verify(rawBody, signature);
        CallbackRequest callback = parse(rawBody);

        // Lock the payment row first: concurrent callbacks for the same payment are serialised.
        Payment payment = payments.findByPaymentIdForUpdate(callback.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(callback.paymentId()));
        PaymentStatus current = payment.getStatus();

        if (processed.existsById(callback.eventId())) {
            lifecycle.audit(payment.getPaymentId(), "CALLBACK_DUPLICATE", current, current,
                    "event=" + callback.eventId());
            log.info("Duplicate callback event {} for {}", callback.eventId(), payment.getPaymentId());
            return new CallbackResponse(payment.getPaymentId(), CallbackOutcome.DUPLICATE, current);
        }
        processed.save(new ProcessedCallback(callback.eventId(), payment.getPaymentId(), callback.status().name()));

        PaymentStatus target = callback.status().toPaymentStatus();
        if (current == target) {
            lifecycle.audit(payment.getPaymentId(), "CALLBACK_IGNORED", current, current,
                    "event=" + callback.eventId() + ", already " + current);
            return new CallbackResponse(payment.getPaymentId(), CallbackOutcome.IGNORED, current);
        }
        if (!current.canTransitionTo(target)) {
            lifecycle.audit(payment.getPaymentId(), "CALLBACK_CONFLICT", current, current,
                    "event=" + callback.eventId() + ", callback status " + target + " conflicts with final state");
            log.warn("Conflicting callback {} ({}) for payment {} already {}", callback.eventId(), target,
                    payment.getPaymentId(), current);
            return new CallbackResponse(payment.getPaymentId(), CallbackOutcome.IGNORED, current);
        }

        lifecycle.transition(payment, target, callback.gatewayReference(), callback.failureReason(),
                "CALLBACK_APPLIED", "event=" + callback.eventId());
        return new CallbackResponse(payment.getPaymentId(), CallbackOutcome.APPLIED, payment.getStatus());
    }

    private CallbackRequest parse(String rawBody) {
        CallbackRequest callback;
        try {
            callback = mapper.readValue(rawBody, CallbackRequest.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Malformed callback payload (status must be SUCCESS or FAILED)");
        }
        if (callback == null) {
            throw new InvalidRequestException("Empty callback payload");
        }
        Set<ConstraintViolation<CallbackRequest>> violations = validator.validate(callback);
        if (!violations.isEmpty()) {
            List<String> details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().toList();
            throw new InvalidRequestException("Invalid callback payload", details);
        }
        return callback;
    }
}
