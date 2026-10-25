package com.matrix.payments.repository;

import com.matrix.payments.domain.PaymentEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {
    List<PaymentEvent> findByPaymentIdOrderByIdAsc(String paymentId);
}
