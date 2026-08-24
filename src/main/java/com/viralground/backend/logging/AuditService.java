package com.viralground.backend.logging;

import com.viralground.backend.config.AuthUser;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private static final Pattern SAFE_FILTER = Pattern.compile("[A-Za-z0-9_.:-]+");
    private final AuditLogRepository repository;

    public com.viralground.backend.dto.admin.AuditLogPageResponse search(
            AuditAction action,
            Integer actorId,
            String resourceType,
            String resourceId,
            Instant fromInclusive,
            Instant toExclusive,
            int page,
            int size) {
        if (actorId != null && actorId <= 0) {
            throw badRequest("actorId must be positive");
        }
        String safeResourceType = optionalFilter(resourceType, 64, "resourceType");
        String safeResourceId = optionalFilter(resourceId, 96, "resourceId");
        if (fromInclusive != null && toExclusive != null && !fromInclusive.isBefore(toExclusive)) {
            throw badRequest("from must be earlier than to");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        var result = repository.search(
                action,
                actorId,
                safeResourceType,
                safeResourceId,
                fromInclusive,
                toExclusive,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return com.viralground.backend.dto.admin.AuditLogPageResponse.from(result);
    }

    public void record(AuthUser actor, AuditAction action, String resourceType,
                       Object resourceId, String outcome, String reason) {
        Integer actorId = actor == null ? null : actor.getId();
        String actorRole = actor == null || actor.getRole() == null ? null : actor.getRole().name();
        record(actorId, actorRole, action, resourceType, resourceId, outcome, reason);
    }

    public void record(Integer actorId, String actorRole, AuditAction action, String resourceType,
                       Object resourceId, String outcome, String reason) {
        String id = resourceId == null ? null : String.valueOf(resourceId);
        try {
            repository.saveAndFlush(new AuditLog(MDC.get(RequestCorrelationFilter.MDC_KEY), actorId, actorRole,
                    action, resourceType, id, outcome, reason));
            log.atInfo()
                    .addKeyValue("event", "audit")
                    .addKeyValue("action", action)
                    .addKeyValue("actorId", actorId)
                    .addKeyValue("resourceType", resourceType)
                    .addKeyValue("resourceId", id)
                    .addKeyValue("outcome", outcome)
                    .log("Audit event persisted");
        } catch (RuntimeException failure) {
            // 원 도메인 트랜잭션은 이미 commit됐을 수 있다. 재시도로 금전/상태 변경이
            // 중복되지 않도록 응답은 보존하고, error 로그를 Sentry/알람으로 승격한다.
            Sentry.captureException(failure);
            log.atError()
                    .addKeyValue("event", "audit_persistence_failed")
                    .addKeyValue("action", action)
                    .addKeyValue("actorId", actorId)
                    .addKeyValue("resourceType", resourceType)
                    .addKeyValue("resourceId", id)
                    .addKeyValue("errorType", failure.getClass().getSimpleName())
                    .log("Audit event persistence failed");
        }
    }

    /** 비인증 공개 요청처럼 actor가 없는 도메인 이벤트를 명시적으로 기록한다. */
    public void recordSystem(AuditAction action, String resourceType,
                             Object resourceId, String outcome, String reason) {
        record((Integer) null, null, action, resourceType, resourceId, outcome, reason);
    }

    private static String optionalFilter(String value, int maxLength, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength || !SAFE_FILTER.matcher(normalized).matches()) {
            throw badRequest(name + " contains unsupported characters");
        }
        return normalized;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
