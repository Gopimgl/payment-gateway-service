package com.matrix.payments.mock;

import com.matrix.payments.config.GatewayProperties;
import com.matrix.payments.dto.CallbackRequest;
import com.matrix.payments.dto.CallbackStatus;
import com.matrix.payments.gateway.GatewayChargeRequest;
import com.matrix.payments.gateway.GatewayChargeResponse;
import com.matrix.payments.gateway.GatewayScenario;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-in for the bank. Behaviour is driven by the request's {@code scenario} (test hook).
 * It also keeps an in-memory ledger so the reconciliation job can ask "what happened to payment X?".
 */
@RestController
@RequestMapping("/mock-gateway")
@Tag(name = "Mock Bank", description = "Simulated bank/provider (demo only)")
public class MockGatewayController {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayController.class);

    private final GatewayProperties props;
    private final MockGatewayCallbackSender callbacks;
    private final Map<String, GatewayChargeResponse> ledger = new ConcurrentHashMap<>();

    public MockGatewayController(GatewayProperties props, MockGatewayCallbackSender callbacks) {
        this.props = props;
        this.callbacks = callbacks;
    }

    @Operation(summary = "Simulate a bank charge")
    @PostMapping("/payments")
    public ResponseEntity<GatewayChargeResponse> charge(@RequestBody GatewayChargeRequest request)
            throws InterruptedException {
        GatewayScenario scenario = request.scenario() == null ? GatewayScenario.SUCCESS : request.scenario();
        String id = request.paymentId();
        String ref = reference(id);
        GatewayChargeResponse approved = new GatewayChargeResponse("APPROVED", ref, null);
        log.info("[mock-bank] charge payment={} amount={} {} scenario={}", id, request.amount(),
                request.currency(), scenario);

        switch (scenario) {
            case DECLINE: {
                GatewayChargeResponse declined = new GatewayChargeResponse("DECLINED", null, "Payment declined by issuer");
                ledger.put(id, declined);
                return ResponseEntity.ok(declined);
            }
            case UNAVAILABLE:
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(new GatewayChargeResponse("ERROR", null, "Bank temporarily unavailable"));
            case ASYNC_SUCCESS:
                ledger.put(id, approved);
                callbacks.schedule(request.callbackUrl(), success(request, ref), props.mockCallbackDelay());
                return ResponseEntity.accepted().body(new GatewayChargeResponse("ACCEPTED", ref, null));
            case DUPLICATE_CALLBACK:
                ledger.put(id, approved);
                callbacks.schedule(request.callbackUrl(), success(request, ref), props.mockCallbackDelay());
                callbacks.schedule(request.callbackUrl(), success(request, ref),
                        props.mockCallbackDelay().plusMillis(300));
                return ResponseEntity.accepted().body(new GatewayChargeResponse("ACCEPTED", ref, null));
            case TIMEOUT:
                // The bank takes the money, but its answer is stalled past the client's read timeout.
                // A late callback (or the reconciliation job) later resolves the UNKNOWN payment.
                ledger.put(id, approved);
                callbacks.schedule(request.callbackUrl(), success(request, ref), props.mockLateCallbackDelay());
                Thread.sleep(props.mockHangTime().toMillis());
                return ResponseEntity.ok(approved);
            case SUCCESS:
            default:
                ledger.put(id, approved);
                return ResponseEntity.ok(approved);
        }
    }

    @Operation(summary = "What does the bank know about this payment? (used by reconciliation)")
    @GetMapping("/payments/{paymentId}")
    public ResponseEntity<GatewayChargeResponse> status(@PathVariable String paymentId) {
        GatewayChargeResponse r = ledger.get(paymentId);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(r);
    }

    private static CallbackRequest success(GatewayChargeRequest r, String ref) {
        return new CallbackRequest("EVT-" + r.paymentId(), r.paymentId(), ref, CallbackStatus.SUCCESS, null);
    }

    private static String reference(String paymentId) {
        return "GW-" + UUID.nameUUIDFromBytes(paymentId.getBytes(StandardCharsets.UTF_8))
                .toString().substring(0, 8).toUpperCase();
    }
}
