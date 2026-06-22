package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 크리에이터의 인스타그램 연동 참조. 애그리게이터(Phyllo)가 동의·토큰을 보관하고,
 * 우리는 연결 참조 id 만 저장(민감 토큰 미보관). creatorId 당 1건(upsert).
 *
 * <p>{@link com.viralground.backend.service.ReelMetricSyncService} 가 CONNECTED 연결을 순회해
 * 릴스 지표를 동기화한다.
 */
@Entity
@Table(name = "creator_instagram_connections",
        uniqueConstraints = @UniqueConstraint(columnNames = {"creator_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatorInstagramConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "creator_id", nullable = false, unique = true)
    private Integer creatorId;

    /** 애그리게이터 식별자. 현재 단일 provider("PHYLLO"). */
    @Column(nullable = false)
    @Builder.Default
    private String provider = "PHYLLO";

    /** 애그리게이터가 부여한 user id (Phyllo user, 크리에이터당 1개 재사용). access token 아님. */
    @Column(name = "provider_user_id")
    private String providerUserId;

    /** 애그리게이터가 부여한 연결 계정 id (account, 동의 완료 시 부여). access token 아님. */
    @Column(name = "provider_account_id")
    private String providerAccountId;

    @Column(name = "ig_username")
    private String igUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConnectionStatus status = ConnectionStatus.PENDING;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

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
