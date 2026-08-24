package com.viralground.backend.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "notification_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_kind", nullable = false, length = 80, updatable = false)
    private String notificationKind;

    @Column(nullable = false, length = 320)
    private String recipient;

    @Column(nullable = false, length = 500)
    private String subject;

    @Column(name = "html_body", nullable = false, columnDefinition = "TEXT")
    private String htmlBody;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 120, updatable = false)
    private String idempotencyKey;

    @Column(name = "provider_message_id", length = 255)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_code", length = 120)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public NotificationOutbox(String notificationKind, String idempotencyKey,
                              String recipient, String subject, String htmlBody, Instant now) {
        if (notificationKind == null || notificationKind.isBlank()) {
            throw new IllegalArgumentException("notificationKind must not be blank");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        this.notificationKind = notificationKind.trim();
        this.recipient = recipient;
        this.subject = subject;
        this.htmlBody = htmlBody;
        this.idempotencyKey = idempotencyKey.trim();
        this.status = NotificationOutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markSent(String providerMessageId, Instant now) {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("providerMessageId must not be blank");
        }
        this.status = NotificationOutboxStatus.SENT;
        this.providerMessageId = providerMessageId.trim();
        this.sentAt = now;
        this.updatedAt = now;
        this.lastErrorCode = null;
        redactTerminalContent("[DELIVERED]");
    }

    /** A newer one-time code replaced this message before dispatch. */
    public void markSuperseded(Instant now) {
        if (this.status != NotificationOutboxStatus.PENDING) return;
        this.status = NotificationOutboxStatus.SUPERSEDED;
        this.updatedAt = now;
        this.nextAttemptAt = now;
        this.lastErrorCode = "SUPERSEDED_BY_NEWER_CODE";
        redactTerminalContent("[SUPERSEDED]");
    }

    public void markFailed(String safeErrorCode, Instant nextAttempt, int maxAttempts, Instant now) {
        this.attempts += 1;
        this.lastErrorCode = safeErrorCode == null ? "UNKNOWN" : safeErrorCode;
        this.updatedAt = now;
        if (this.attempts >= maxAttempts) {
            this.status = NotificationOutboxStatus.DEAD_LETTER;
            this.nextAttemptAt = now;
            // DLQ 재발송은 원문을 보존하지 않고 새 알림을 생성하는 승인 절차로 처리한다.
            redactTerminalContent("[REDACTED_AFTER_FAILURE]");
        } else {
            this.nextAttemptAt = nextAttempt;
        }
    }

    private void redactTerminalContent(String bodyMarker) {
        // providerMessageId/status/idempotency evidence만 남기고 이메일 PII와 업무 문구는 제거한다.
        this.recipient = "[REDACTED]";
        this.subject = "[REDACTED]";
        this.htmlBody = bodyMarker;
    }
}
