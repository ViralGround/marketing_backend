package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_applications",
        uniqueConstraints = @UniqueConstraint(columnNames = {"campaign_id", "creator_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CampaignApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "campaign_id", nullable = false)
    private Integer campaignId;

    @Column(name = "creator_id", nullable = false)
    private Integer creatorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "submission_url")
    private String submissionUrl;

    @Column(name = "video_file_key")
    private String videoFileKey;

    @Column(name = "video_content_type")
    private String videoContentType;

    @Column(name = "video_size_bytes")
    private Long videoSizeBytes;

    @Column(name = "resubmission_count", nullable = false)
    @Builder.Default
    private Integer resubmissionCount = 0;

    @Column(name = "review_comment", columnDefinition = "TEXT")
    private String reviewComment;

    @Column(name = "reward_paid_amount")
    private Integer rewardPaidAmount;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private LocalDateTime appliedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /**
     * Nonfinancial managed-beta completion marker. The persisted status is the
     * old-backend terminal SETTLED enum so rollback cannot re-process content;
     * the new API presents this marker as COMPLETED without implying payment.
     */
    @Column(name = "content_approved_at")
    private LocalDateTime contentApprovedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id", insertable = false, updatable = false)
    private Campaign campaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", insertable = false, updatable = false)
    private Member creator;

    @PrePersist
    protected void onCreate() {
        appliedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isCompletedWork() {
        return status == ApplicationStatus.SETTLED || contentApprovedAt != null;
    }

    public String getApiStatus() {
        return contentApprovedAt != null ? "COMPLETED" : status.name();
    }
}
