package com.viralground.backend.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {
    @Test
    void stripsControlCharactersAndBoundsReason() {
        AuditLog audit = new AuditLog("request-12345678", 1, "ADMIN",
                AuditAction.MEMBER_STATUS_CHANGED, "member", "2", "SUCCESS",
                "approved\r\n" + "x".repeat(400));

        assertThat(audit.getReason()).doesNotContain("\r", "\n").hasSize(240);
    }
}
