package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "escrow_transactions", indexes = {
        @Index(name = "idx_escrow_campaign", columnList = "campaign_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EscrowTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "campaign_id", nullable = false)
    private Integer campaignId;

    @Column(name = "application_id")
    private Integer applicationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EscrowTxType type;

    @Column(nullable = false)
    private Integer amount;

    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
