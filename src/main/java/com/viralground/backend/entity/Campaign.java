package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaigns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Column(name = "reward_amount", nullable = false)
    private Integer rewardAmount;

    @Column(name = "total_budget", nullable = false)
    @Builder.Default
    private Integer totalBudget = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "escrow_status", nullable = false)
    @Builder.Default
    private EscrowStatus escrowStatus = EscrowStatus.NONE;

    @Column(name = "deposit_requested_at")
    private LocalDateTime depositRequestedAt;

    @Column(name = "funded_at")
    private LocalDateTime fundedAt;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    private LocalDateTime deadline;

    @Column(name = "max_participants", nullable = false)
    private Integer maxParticipants;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "created_by_id", nullable = false)
    private Integer createdById;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", insertable = false, updatable = false)
    private Member createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
