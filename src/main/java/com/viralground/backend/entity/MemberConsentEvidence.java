package com.viralground.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

/**
 * 회원이 특정 버전의 법적 문서에 동의했다는 append-only 증적.
 *
 * <p>고의로 IP, User-Agent 등 불필요한 온라인 식별자를 저장하지 않는다. 엔티티의
 * setter/update mapping을 제거하고 {@link Immutable}로 표시했으며, 최종 방어선은
 * Flyway migration의 UPDATE/DELETE 차단 trigger다.</p>
 */
@Entity
@Immutable
@Table(
        name = "member_consent_evidence",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_member_consent_type_version",
                columnNames = {"member_id", "consent_type", "document_version"}),
        indexes = @Index(name = "idx_member_consent_agreed_at", columnList = "agreed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberConsentEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Integer memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, updatable = false, length = 64)
    private ConsentType consentType;

    @Column(name = "document_version", nullable = false, updatable = false, length = 80)
    private String documentVersion;

    @Column(name = "agreed_at", nullable = false, updatable = false)
    private Instant agreedAt;

    public MemberConsentEvidence(
            Integer memberId,
            ConsentType consentType,
            String documentVersion,
            Instant agreedAt
    ) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.consentType = Objects.requireNonNull(consentType, "consentType");
        if (documentVersion == null || documentVersion.isBlank()) {
            throw new IllegalArgumentException("documentVersion must not be blank");
        }
        if (documentVersion.length() > 80) {
            throw new IllegalArgumentException("documentVersion must not exceed 80 characters");
        }
        this.documentVersion = documentVersion;
        this.agreedAt = Objects.requireNonNull(agreedAt, "agreedAt");
    }
}
