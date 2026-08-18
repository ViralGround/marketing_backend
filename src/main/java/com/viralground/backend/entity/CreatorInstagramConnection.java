package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 크리에이터의 Meta Instagram Graph API 연결. access token은 평문으로 저장하지 않고
 * {@code InstagramTokenCipher}로 암호화한 값만 저장한다. creatorId 당 1건(upsert).
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

    /** 공급자 식별자. 운영은 META, 명시적인 로컬 개발에서만 MOCK. */
    @Column(nullable = false)
    @Builder.Default
    private String provider = "META";

    /** Meta Instagram professional account id. */
    @Column(name = "provider_user_id")
    private String providerUserId;

    /** Meta Instagram professional account id. 이전 컬럼 호환을 위해 함께 유지한다. */
    @Column(name = "provider_account_id", unique = true)
    private String providerAccountId;

    @Column(name = "ig_username")
    private String igUsername;

    /** AES-GCM으로 암호화한 장기 access token. 절대 로그/응답에 포함하지 않는다. */
    @Column(name = "encrypted_access_token", columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "access_token_expires_at")
    private LocalDateTime accessTokenExpiresAt;

    @Column(name = "token_refreshed_at")
    private LocalDateTime tokenRefreshedAt;

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
