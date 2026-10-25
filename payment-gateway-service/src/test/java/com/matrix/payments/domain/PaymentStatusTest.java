package com.matrix.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void pendingCanMoveToAnyOtherState() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.SUCCESS)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.UNKNOWN)).isTrue();
    }

    @Test
    void unknownResolvesOnlyToFinalStates() {
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.SUCCESS)).isTrue();
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    void finalStatesNeverChange() {
        for (PaymentStatus next : PaymentStatus.values()) {
            assertThat(PaymentStatus.SUCCESS.canTransitionTo(next)).isFalse();
            assertThat(PaymentStatus.FAILED.canTransitionTo(next)).isFalse();
        }
    }
}
