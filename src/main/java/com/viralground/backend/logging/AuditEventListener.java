package com.viralground.backend.logging;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 성공한 도메인 변경만 원 트랜잭션 commit 뒤 별도 트랜잭션에 영구 보관한다. */
@Component
@RequiredArgsConstructor
public class AuditEventListener {
    private final AuditService auditService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuditEvent(AuditEvent event) {
        auditService.record(event.actorId(), event.actorRole(), event.action(), event.resourceType(),
                event.resourceId(), event.outcome(), event.reason());
    }
}
