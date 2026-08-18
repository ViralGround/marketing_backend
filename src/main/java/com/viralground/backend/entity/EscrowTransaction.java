package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "escrow_transactions",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_escrow_idempotency", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uq_escrow_provider_tx", columnNames = {"provider", "provider_tx_id"})
        },
        indexes = {
                @Index(name = "idx_escrow_campaign", columnList = "campaign_id"),
                @Index(name = "idx_escrow_created_at", columnList = "created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EscrowTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Integer campaignId;

    @Column(name = "application_id", updatable = false)
    private Integer applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EscrowTxType type;

    @Column(nullable = false, updatable = false)
    private Integer amount;

    @Column(nullable = false, length = 3, updatable = false)
    @Builder.Default
    private String currency = "KRW";

    @Column(name = "operation_id", nullable = false, length = 64, updatable = false)
    private String operationId;

    @Column(name = "idempotency_key", nullable = false, length = 160, updatable = false)
    private String idempotencyKey;

    @Column(nullable = false, length = 50, updatable = false)
    private String provider;

    @Column(name = "provider_tx_id", nullable = false, length = 200, updatable = false)
    private String providerTxId;

    @Column(name = "actor_member_id", updatable = false)
    private Integer actorMemberId;

    @Column(name = "actor_type", nullable = false, length = 30, updatable = false)
    private String actorType;

    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    /** 거래 반영 직후 해당 캠페인에서 사용 가능한 에스크로 잔액 스냅샷. */
    @Column(name = "balance_after", nullable = false, updatable = false)
    private Integer balanceAfter;

    /** 하위 호환 표시용. provider transaction id 대신 운영 사유를 노출한다. */
    @Column(length = 500, updatable = false)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
