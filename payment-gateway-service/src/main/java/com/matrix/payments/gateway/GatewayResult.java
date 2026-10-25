package com.matrix.payments.gateway;

public record GatewayResult(GatewayOutcome outcome, String gatewayReference, String reason) {
    public static GatewayResult approved(String ref) { return new GatewayResult(GatewayOutcome.APPROVED, ref, null); }
    public static GatewayResult declined(String reason) { return new GatewayResult(GatewayOutcome.DECLINED, null, reason); }
    public static GatewayResult accepted(String ref) { return new GatewayResult(GatewayOutcome.ACCEPTED, ref, null); }
    public static GatewayResult unknown(String reason) { return new GatewayResult(GatewayOutcome.UNKNOWN, null, reason); }
    public static GatewayResult unavailable(String reason) { return new GatewayResult(GatewayOutcome.UNAVAILABLE, null, reason); }
    public static GatewayResult notFound() { return new GatewayResult(GatewayOutcome.NOT_FOUND, null, "Bank has no record of this payment"); }
}
