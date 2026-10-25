package com.matrix.payments.gateway;

import java.math.BigDecimal;

/** What we send to the bank: who/what to charge, the test scenario (mock only) and where to call back. */
public record GatewayChargeRequest(String paymentId, BigDecimal amount, String currency,
                                   GatewayScenario scenario, String callbackUrl) {
}
