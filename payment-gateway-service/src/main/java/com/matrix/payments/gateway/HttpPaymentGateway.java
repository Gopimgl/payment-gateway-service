package com.matrix.payments.gateway;

import com.matrix.payments.config.GatewayProperties;
import java.net.SocketTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for the payment gateway.
 * <ul>
 *   <li>5xx / connection failures are retried with linear backoff. This is safe because the
 *       paymentId is sent as an Idempotency-Key.</li>
 *   <li>A read timeout is NOT retried and NOT treated as failure: the provider may have
 *       processed the charge, so the outcome is UNKNOWN until a callback arrives.</li>
 * </ul>
 */
@Component
public class HttpPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpPaymentGateway.class);

    private final RestClient client;
    private final GatewayProperties props;

    public HttpPaymentGateway(RestClient gatewayRestClient, GatewayProperties props) {
        this.client = gatewayRestClient;
        this.props = props;
    }

    @Override
    public GatewayResult charge(GatewayChargeRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                GatewayChargeResponse response = client.post()
                        .uri("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", request.paymentId())
                        .body(request)
                        .retrieve()
                        .body(GatewayChargeResponse.class);
                return toResult(response);
            } catch (HttpClientErrorException e) {
                log.warn("Gateway rejected request for {}: HTTP {}", request.paymentId(), e.getStatusCode().value());
                return GatewayResult.declined("Gateway rejected request: HTTP " + e.getStatusCode().value());
            } catch (HttpServerErrorException e) {
                log.warn("Gateway 5xx for {} (attempt {}/{}): HTTP {}", request.paymentId(), attempt,
                        props.maxAttempts(), e.getStatusCode().value());
                if (attempt >= props.maxAttempts()) {
                    return GatewayResult.unavailable("Gateway unavailable: HTTP " + e.getStatusCode().value());
                }
            } catch (ResourceAccessException e) {
                if (isReadTimeout(e)) {
                    log.warn("Gateway read timeout for {}; outcome unknown", request.paymentId());
                    return GatewayResult.unknown("Gateway timed out; payment outcome unknown");
                }
                log.warn("Gateway unreachable for {} (attempt {}/{}): {}", request.paymentId(), attempt,
                        props.maxAttempts(), e.getMessage());
                if (attempt >= props.maxAttempts()) {
                    return GatewayResult.unavailable("Gateway unreachable");
                }
            } catch (RestClientException e) {
                // A read timeout while waiting for the response headers is wrapped in a plain
                // RestClientException (not ResourceAccessException), so it must be handled here too.
                // Either way the gateway may have processed the charge: outcome is unknown.
                if (isReadTimeout(e)) {
                    log.warn("Gateway read timeout for {}; outcome unknown", request.paymentId());
                    return GatewayResult.unknown("Gateway timed out; payment outcome unknown");
                }
                log.warn("Unreadable gateway response for {}: {}", request.paymentId(), e.getMessage());
                return GatewayResult.unknown("Unreadable gateway response; payment outcome unknown");
            }
            try {
                Thread.sleep(props.retryBackoff().multipliedBy(attempt).toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return GatewayResult.unknown("Interrupted while calling gateway");
            }
        }
    }

    @Override
    public GatewayResult queryStatus(String paymentId) {
        try {
            GatewayChargeResponse r = client.get().uri("/payments/{id}", paymentId).retrieve()
                    .body(GatewayChargeResponse.class);
            return toResult(r);
        } catch (HttpClientErrorException.NotFound e) {
            return GatewayResult.notFound();
        } catch (RestClientException e) {
            log.warn("Status query failed for {}: {}", paymentId, e.getMessage());
            return GatewayResult.unknown("Status query failed: " + e.getMessage());
        }
    }

    private static GatewayResult toResult(GatewayChargeResponse r) {
        if (r == null || r.status() == null) {
            return GatewayResult.unknown("Empty response from gateway");
        }
        return switch (r.status()) {
            case "APPROVED" -> GatewayResult.approved(r.gatewayReference());
            case "DECLINED" -> GatewayResult.declined(r.failureReason() != null ? r.failureReason() : "Payment declined");
            case "ACCEPTED" -> GatewayResult.accepted(r.gatewayReference());
            default -> GatewayResult.unknown("Unrecognised gateway status: " + r.status());
        };
    }

    private static boolean isReadTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException
                    && t.getMessage() != null
                    && t.getMessage().toLowerCase().contains("read")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
