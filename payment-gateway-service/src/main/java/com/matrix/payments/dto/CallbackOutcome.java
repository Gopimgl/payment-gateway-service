package com.matrix.payments.dto;

public enum CallbackOutcome {
    /** Callback changed the payment status. */
    APPLIED,
    /** Same event ID was already processed. */
    DUPLICATE,
    /** Callback was valid but did not change anything (same or conflicting final state). */
    IGNORED
}
