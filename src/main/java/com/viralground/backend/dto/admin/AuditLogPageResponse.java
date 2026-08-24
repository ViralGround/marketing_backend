package com.viralground.backend.dto.admin;

import org.springframework.data.domain.Page;

import java.util.List;

public record AuditLogPageResponse(
        List<AuditLogResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static AuditLogPageResponse from(Page<com.viralground.backend.logging.AuditLog> result) {
        return new AuditLogPageResponse(
                result.getContent().stream().map(AuditLogResponse::from).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }
}
