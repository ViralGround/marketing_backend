package com.viralground.backend.logging;

/**
 * 도메인 트랜잭션과 감사 로그 저장을 분리하기 위한 최소 이벤트다.
 * 이메일, 이름, 요청 본문, 토큰과 같은 개인정보·비밀은 절대 담지 않는다.
 */
public record AuditEvent(
        Integer actorId,
        String actorRole,
        AuditAction action,
        String resourceType,
        Object resourceId,
        String outcome,
        String reason
) {
}
