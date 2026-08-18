package com.viralground.backend.repository;

import com.viralground.backend.entity.PaymentLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentLedgerEntryRepository extends JpaRepository<PaymentLedgerEntry, Long> {
    List<PaymentLedgerEntry> findByOperationIdOrderByDirectionAsc(String operationId);
}
