package com.matrix.payments.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matrix.payments.dto.CallbackRequest;
import com.matrix.payments.service.SignatureService;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Simulates a provider calling our webhook later, with an HMAC signature. */
@Component
public class MockGatewayCallbackSender {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayCallbackSender.class);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final RestClient client = RestClient.create();
    private final SignatureService signatures;
    private final ObjectMapper mapper;

    public MockGatewayCallbackSender(SignatureService signatures, ObjectMapper mapper) {
        this.signatures = signatures;
        this.mapper = mapper;
    }

    public void schedule(String callbackUrl, CallbackRequest payload, Duration delay) {
        scheduler.schedule(() -> send(callbackUrl, payload), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void send(String url, CallbackRequest payload) {
        try {
            String body = mapper.writeValueAsString(payload);
            client.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signatures.sign(body))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[mock-gateway] callback delivered: event={} payment={} status={}",
                    payload.eventId(), payload.paymentId(), payload.status());
        } catch (Exception e) {
            log.error("[mock-gateway] callback delivery failed for {}: {}", payload.paymentId(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
