package com.matrix.payments.web;

import com.matrix.payments.dto.ApiError;
import com.matrix.payments.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ApiError> notFound(PaymentNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler({IdempotencyConflictException.class, InvalidStatusTransitionException.class})
    ResponseEntity<ApiError> conflict(RuntimeException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(InvalidSignatureException.class)
    ResponseEntity<ApiError> badSignature(InvalidSignatureException ex, HttpServletRequest req) {
        log.warn("Rejected callback with invalid signature from {}", req.getRemoteAddr());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalid(InvalidRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req, ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", req, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> badRequest(Exception ex, HttpServletRequest req) {
        String message = ex instanceof MissingRequestHeaderException m
                ? "Missing required header: " + m.getHeaderName()
                : "Malformed request: check JSON syntax, enum values and parameter types";
        return build(HttpStatus.BAD_REQUEST, message, req, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> fallback(Exception ex, HttpServletRequest req) {
        if (ex instanceof org.springframework.web.ErrorResponse er) { // 404, 405, 415 ... raised by Spring MVC
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            return build(status, er.getBody().getDetail(), req, List.of());
        }
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error. Quote the correlation ID when reporting.",
                req, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest req,
                                           List<String> details) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message,
                req.getRequestURI(), MDC.get(CorrelationIdFilter.MDC_KEY), details);
        return ResponseEntity.status(status).body(body);
    }
}
