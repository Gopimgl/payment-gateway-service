package com.matrix.payments.repository;

import com.matrix.payments.domain.ProcessedCallback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCallbackRepository extends JpaRepository<ProcessedCallback, String> {
}
