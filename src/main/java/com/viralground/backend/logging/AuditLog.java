package com.viralground.backend.logging;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_actor_created", columnList = "actor_id,created_at"),
        @Index(name = "idx_audit_logs_resource", columnList = "resource_type,resource_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "actor_id", updatable = false)
    private Integer actorId;

    @Column(name = "actor_role", length = 24, updatable = false)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48, updatable = false)
    private AuditAction action;

    @Column(name = "resource_type", nullable = false, length = 64, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", length = 96, updatable = false)
    private String resourceId;

    @Column(nullable = false, length = 24, updatable = false)
    private String outcome;

    @Column(length = 240, updatable = false)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(String requestId, Integer actorId, String actorRole, AuditAction action,
                    String resourceType, String resourceId, String outcome, String reason) {
        this.requestId = requestId;
        this.actorId = actorId;
        this.actorRole = boundedIdentifier(actorRole, 24);
        this.action = java.util.Objects.requireNonNull(action, "action");
        this.resourceType = boundedRequiredIdentifier(resourceType, 64, "resourceType");
        this.resourceId = boundedIdentifier(resourceId, 96);
        this.outcome = boundedRequiredIdentifier(outcome, 24, "outcome");
        this.reason = sanitize(reason);
        this.createdAt = Instant.now();
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return safe.substring(0, Math.min(safe.length(), 240));
    }

    private static String boundedRequiredIdentifier(String value, int max, String field) {
        String normalized = boundedIdentifier(value, max);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String boundedIdentifier(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return safe.substring(0, Math.min(safe.length(), max));
    }
}
