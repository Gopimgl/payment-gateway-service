package com.matrix.payments.gateway;

/** status is one of APPROVED, DECLINED, ACCEPTED (result will arrive via callback). */
public record GatewayChargeResponse(String status, String gatewayReference, String failureReason) {
}
