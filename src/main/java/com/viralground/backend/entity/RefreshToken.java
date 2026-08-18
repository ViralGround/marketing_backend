package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_member", columnList = "member_id,revoked_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {
    @Id
    @Column(name = "token_id", length = 64)
    private String tokenId;

    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @Column(name = "family_id", nullable = false, length = 64)
    private String familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by", length = 64)
    private String replacedBy;

    public RefreshToken(String tokenId, Integer memberId, String familyId, Instant expiresAt) {
        this.tokenId = tokenId;
        this.memberId = memberId;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void rotateTo(String replacementTokenId) {
        this.revokedAt = Instant.now();
        this.replacedBy = replacementTokenId;
    }

    public void revoke() {
        if (this.revokedAt == null) this.revokedAt = Instant.now();
    }
}
