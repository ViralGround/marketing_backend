package com.viralground.backend.notification;

import com.viralground.backend.config.PostgresTransactionAdvisoryLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxService {

    private final NotificationOutboxRepository repository;
    private final Clock clock;
    private final PostgresTransactionAdvisoryLock transactionLock;

    @Value("${notification.outbox.enabled:true}")
    private boolean outboxEnabled;

    /**
     * Generates an opaque per-notification key. Callers create it once per business
     * request and persist it with the outbox row; recipient addresses and one-time
     * codes must never be embedded in provider idempotency keys.
     */
    public static String newIdempotencyKey() {
        return "vg-outbox-" + UUID.randomUUID();
    }

    /** 호출자의 transaction에 참여해 업무 데이터와 알림 생성이 함께 commit/rollback된다. */
    @Transactional
    public void enqueue(String notificationKind, String idempotencyKey,
                        String recipient, String subject, String htmlBody) {
        if (!outboxEnabled) {
            log.info("event=notification_skipped reason=outbox_disabled");
            return;
        }
        repository.save(new NotificationOutbox(
                notificationKind, idempotencyKey, recipient, subject, htmlBody, clock.instant()));
    }

    /**
     * One-time-code delivery is replaceable: before a new code mail is queued,
     * every still-undispatched mail of the same kind and recipient is locked,
     * redacted, and made ineligible for the dispatcher in the same transaction.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void supersedePendingAndEnqueue(String notificationKind, String idempotencyKey,
                                           String recipient, String subject, String htmlBody) {
        if (!outboxEnabled) {
            log.info("event=notification_skipped reason=outbox_disabled");
            return;
        }
        String canonicalKind = notificationKind == null ? "" : notificationKind.trim();
        String canonicalRecipient = recipient == null ? "" : recipient.trim().toLowerCase(java.util.Locale.ROOT);
        if (canonicalKind.isBlank() || canonicalRecipient.isBlank()) {
            throw new IllegalArgumentException("notification kind and recipient are required");
        }
        transactionLock.lock(
                PostgresTransactionAdvisoryLock.Scope.NOTIFICATION_OUTBOX,
                canonicalKind + "\0" + canonicalRecipient);
        var now = clock.instant();
        repository.findByNotificationKindAndRecipientAndStatusOrderByCreatedAtAsc(
                        canonicalKind, canonicalRecipient, NotificationOutboxStatus.PENDING)
                .forEach(pending -> pending.markSuperseded(now));
        repository.save(new NotificationOutbox(
                canonicalKind, idempotencyKey, canonicalRecipient, subject, htmlBody, now));
    }
}
