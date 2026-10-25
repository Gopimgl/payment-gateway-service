package com.matrix.payments.gateway;

/**
 * Port to an external payment provider. Implementations must never throw for provider
 * problems; they translate them into a {@link GatewayResult}.
 */
public interface PaymentGateway {
    GatewayResult charge(GatewayChargeRequest request);

    /** Asks the provider what happened to a payment (used by reconciliation). */
    GatewayResult queryStatus(String paymentId);
}
