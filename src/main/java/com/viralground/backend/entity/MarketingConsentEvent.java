package com.viralground.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;

@Entity
@Immutable
@Table(name = "marketing_consent_events", indexes = {
        @Index(name = "idx_marketing_consent_member_time", columnList = "member_id,occurred_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketingConsentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Integer memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private MarketingConsentAction action;

    @Column(name = "document_version", nullable = false, updatable = false, length = 80)
    private String documentVersion;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    public MarketingConsentEvent(Integer memberId, MarketingConsentAction action,
                                 String documentVersion, Instant occurredAt) {
        this.memberId = Objects.requireNonNull(memberId, "memberId");
        this.action = Objects.requireNonNull(action, "action");
        if (documentVersion == null || documentVersion.isBlank() || documentVersion.length() > 80) {
            throw new IllegalArgumentException("documentVersion must contain 1 to 80 characters");
        }
        this.documentVersion = documentVersion;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
