package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 한 결제 거래당 DEBIT/CREDIT 두 행을 기록하는 append-only 이중 원장이다.
 * 애플리케이션은 UPDATE/DELETE를 호출하지 않으며 운영 DB migration도 이를 trigger로 차단한다.
 */
@Entity
@Table(name = "payment_ledger_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_payment_ledger_operation_direction",
                columnNames = {"operation_id", "direction"}),
        indexes = {
                @Index(name = "idx_payment_ledger_campaign", columnList = "campaign_id"),
                @Index(name = "idx_payment_ledger_transaction", columnList = "escrow_transaction_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PaymentLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "escrow_transaction_id", nullable = false, updatable = false)
    private Integer escrowTransactionId;

    @Column(name = "operation_id", nullable = false, length = 64, updatable = false)
    private String operationId;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Integer campaignId;

    @Column(name = "application_id", updatable = false)
    private Integer applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentLedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentLedgerDirection direction;

    @Column(nullable = false, updatable = false)
    private Integer amount;

    @Column(nullable = false, length = 3, updatable = false)
    @Builder.Default
    private String currency = "KRW";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
