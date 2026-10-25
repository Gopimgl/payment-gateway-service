package com.matrix.payments.repository;

import com.matrix.payments.domain.Payment;
import com.matrix.payments.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentId(String paymentId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    List<Payment> findByStatusInAndUpdatedAtBefore(Collection<PaymentStatus> statuses, Instant cutoff);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    /** Row lock so concurrent callbacks / gateway responses for one payment are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.paymentId = :paymentId")
    Optional<Payment> findByPaymentIdForUpdate(@Param("paymentId") String paymentId);
}
