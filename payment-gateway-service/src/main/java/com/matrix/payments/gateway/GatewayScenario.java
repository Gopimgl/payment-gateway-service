package com.matrix.payments.gateway;

/**
 * Test hook: tells the mock bank how to behave. A real client never chooses the outcome;
 * in production this field is removed and the bank decides.
 */
public enum GatewayScenario {
    /** Bank approves immediately. */
    SUCCESS,
    /** Bank declines the payment. */
    DECLINE,
    /** Bank stalls past the client's read timeout, then sends a late callback. */
    TIMEOUT,
    /** Bank answers HTTP 503. */
    UNAVAILABLE,
    /** Bank accepts, then confirms later by callback. */
    ASYNC_SUCCESS,
    /** Bank accepts, then sends the same callback twice. */
    DUPLICATE_CALLBACK
}
