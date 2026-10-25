package com.matrix.payments.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.matrix.payments.domain.PaymentStatus;
import com.matrix.payments.dto.PaymentResponse;
import com.matrix.payments.exception.IdempotencyConflictException;
import com.matrix.payments.exception.PaymentNotFoundException;
import com.matrix.payments.service.PaymentService;
import com.matrix.payments.service.ReconciliationService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PaymentService service;
    @MockBean ReconciliationService reconciliation;

    private static final String VALID = "{\"orderId\":\"ORD-1\",\"amount\":1000.00,\"currency\":\"INR\"}";

    private static PaymentResponse response() {
        return new PaymentResponse("PAY-1", "ORD-1", new BigDecimal("1000.00"), "INR", PaymentStatus.SUCCESS,
                "GW-1", null, Instant.now(), Instant.now());
    }

    @Test
    void createsPayment() throws Exception {
        when(service.create(eq("key-1"), any())).thenReturn(new PaymentService.CreateResult(response(), false));

        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/payments/PAY-1"))
                .andExpect(jsonPath("$.paymentId").value("PAY-1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void replayReturns200WithHeader() throws Exception {
        when(service.create(eq("key-1"), any())).thenReturn(new PaymentService.CreateResult(response(), true));

        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void rejectsNegativeAmount() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"amount\":-5,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("amount")));
    }

    @Test
    void rejectsZeroAmountAndBadCurrency() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"amount\":0,\"currency\":\"XYZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void requiresIdempotencyKeyHeader() throws Exception {
        mvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyConflictReturns409() throws Exception {
        when(service.create(eq("key-1"), any())).thenThrow(new IdempotencyConflictException("key-1"));

        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownPaymentReturns404() throws Exception {
        when(service.get("PAY-404")).thenThrow(new PaymentNotFoundException("PAY-404"));

        mvc.perform(get("/api/v1/payments/PAY-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void reconcileEndpointReturnsPayment() throws Exception {
        when(reconciliation.reconcileNow("PAY-1")).thenReturn(response());

        mvc.perform(post("/api/v1/payments/PAY-1/reconcile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("PAY-1"));
    }

    @Test
    void rejectsUnknownScenarioValue() throws Exception {
        mvc.perform(post("/api/v1/payments").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"amount\":10,\"currency\":\"INR\",\"scenario\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest());
    }
}
