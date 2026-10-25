package com.matrix.payments.domain;

public enum PaymentStatus {
    /** Created locally; gateway outcome not yet known (or gateway will call back). */
    PENDING,
    /** Gateway call timed out; the payment may or may not have been processed. */
    UNKNOWN,
    SUCCESS,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }

    /** State machine: SUCCESS and FAILED are final; UNKNOWN can only resolve to a final state. */
    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING -> next != PENDING;
            case UNKNOWN -> next == SUCCESS || next == FAILED;
            case SUCCESS, FAILED -> false;
        };
    }
}
