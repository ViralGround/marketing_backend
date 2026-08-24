package com.viralground.backend.dto.admin;

import com.viralground.backend.logging.AuditLog;

import java.time.Instant;

/** PII-safe operations projection. Free-form reason text is intentionally omitted. */
public record AuditLogResponse(
        Long id,
        String requestId,
        Integer actorId,
        String actorRole,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        Instant createdAt
) {
    public static AuditLogResponse from(AuditLog audit) {
        return new AuditLogResponse(
                audit.getId(),
                audit.getRequestId(),
                audit.getActorId(),
                audit.getActorRole(),
                audit.getAction().name(),
                audit.getResourceType(),
                audit.getResourceId(),
                audit.getOutcome(),
                audit.getCreatedAt());
    }
}
