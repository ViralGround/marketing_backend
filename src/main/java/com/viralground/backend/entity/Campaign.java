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

    @Column(name = "thumbnail_file_key")
    private String thumbnailFileKey;

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

    /**
     * 숨김(soft hide) 처리 시점. null 이면 노출, 값이 있으면 사용자/크리에이터에게 미노출.
     * hard delete 가 자식 데이터(지원·에스크로)로 막힌 경우의 대체 운영 수단.
     */
    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    public boolean isHidden() {
        return hiddenAt != null;
    }

    /**
     * 랜딩 페이지 노출용 "대표 캠페인" 지정 순번. null 이면 비대표,
     * 값이 있으면 오름차순으로 정렬해 노출한다. 최대 3건 제약은 서비스 레벨에서 강제.
     */
    @Column(name = "featured_order")
    private Integer featuredOrder;

    public boolean isFeatured() {
        return featuredOrder != null;
    }

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
