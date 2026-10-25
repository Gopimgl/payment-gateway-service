package com.matrix.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end test against the REAL local PostgreSQL (Flyway schema, row locks, unique constraints).
 * Not run by a plain "mvn test". Run it with:  mvn test -Dtest=PaymentFlowIT
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=18080")
class PaymentFlowIT {

    @Autowired TestRestTemplate rest;

    private ResponseEntity<Map> create(String key, String amount, String scenario) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        String body = "{\"orderId\":\"ORD-IT\",\"amount\":" + amount + ",\"currency\":\"INR\",\"scenario\":\"" + scenario + "\"}";
        return rest.exchange("/api/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private Map<?, ?> get(String paymentId) {
        return rest.getForObject("/api/v1/payments/" + paymentId, Map.class);
    }

    private String waitForStatus(String paymentId, String expected, int seconds) throws InterruptedException {
        String status = null;
        for (int i = 0; i < seconds * 4; i++) {
            status = String.valueOf(get(paymentId).get("status"));
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(250);
        }
        return status;
    }

    @Test
    void successfulPaymentIsStored() {
        ResponseEntity<Map> r = create(UUID.randomUUID().toString(), "1000.00", "SUCCESS");
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getBody().get("status")).isEqualTo("SUCCESS");
        assertThat(get((String) r.getBody().get("paymentId")).get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void declinedPaymentIsFailed() {
        ResponseEntity<Map> r = create(UUID.randomUUID().toString(), "500.00", "DECLINE");
        assertThat(r.getBody().get("status")).isEqualTo("FAILED");
        assertThat(r.getBody().get("failureReason")).isEqualTo("Payment declined by issuer");
    }

    @Test
    void sameKeyReplaysAndDifferentBodyConflicts() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = create(key, "100.00", "SUCCESS");
        ResponseEntity<Map> second = create(key, "100.00", "SUCCESS");
        ResponseEntity<Map> conflict = create(key, "999.00", "SUCCESS");

        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("paymentId")).isEqualTo(first.getBody().get("paymentId"));
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOnePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<ResponseEntity<Map>>> tasks = List.of(
                () -> create(key, "42.00", "SUCCESS"), () -> create(key, "42.00", "SUCCESS"),
                () -> create(key, "42.00", "SUCCESS"), () -> create(key, "42.00", "SUCCESS"),
                () -> create(key, "42.00", "SUCCESS"));
        Set<Object> ids = new HashSet<>();
        for (Future<ResponseEntity<Map>> f : pool.invokeAll(tasks)) {
            ids.add(f.get().getBody().get("paymentId"));
        }
        pool.shutdown();
        assertThat(ids).hasSize(1);
    }

    @Test
    void timeoutBecomesUnknownThenSuccessViaLateCallback() throws Exception {
        ResponseEntity<Map> r = create(UUID.randomUUID().toString(), "250.00", "TIMEOUT");
        String id = (String) r.getBody().get("paymentId");
        assertThat(r.getBody().get("status")).isEqualTo("UNKNOWN");
        assertThat(waitForStatus(id, "SUCCESS", 15)).isEqualTo("SUCCESS");
    }

    @Test
    void duplicateCallbackIsRecordedButNotReapplied() throws Exception {
        ResponseEntity<Map> r = create(UUID.randomUUID().toString(), "60.00", "DUPLICATE_CALLBACK");
        String id = (String) r.getBody().get("paymentId");
        assertThat(waitForStatus(id, "SUCCESS", 8)).isEqualTo("SUCCESS");
        Thread.sleep(1500);
        List<?> events = rest.getForObject("/api/v1/payments/" + id + "/events", List.class);
        long duplicates = events.stream().filter(e -> "CALLBACK_DUPLICATE".equals(((Map<?, ?>) e).get("eventType"))).count();
        assertThat(duplicates).isEqualTo(1);
    }

    @Test
    void manualReconcileResolvesTimedOutPayment() throws Exception {
        ResponseEntity<Map> r = create(UUID.randomUUID().toString(), "75.00", "TIMEOUT");
        String id = (String) r.getBody().get("paymentId");
        Map<?, ?> reconciled = rest.postForObject("/api/v1/payments/" + id + "/reconcile", null, Map.class);
        assertThat(reconciled.get("status")).isEqualTo("SUCCESS");
    }
}
