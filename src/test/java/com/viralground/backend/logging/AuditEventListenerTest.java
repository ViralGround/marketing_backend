package com.viralground.backend.logging;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditEventListenerTest {
    @Test
    void forwardsOnlyStructuredNonSensitiveAuditFields() {
        AuditService service = mock(AuditService.class);
        AuditEventListener listener = new AuditEventListener(service);
        AuditEvent event = new AuditEvent(7, "ADMIN", AuditAction.MEMBER_STATUS_CHANGED,
                "member", 22, "SUCCESS", "APPROVED");

        listener.onAuditEvent(event);

        verify(service).record(7, "ADMIN", AuditAction.MEMBER_STATUS_CHANGED,
                "member", 22, "SUCCESS", "APPROVED");
    }
}
