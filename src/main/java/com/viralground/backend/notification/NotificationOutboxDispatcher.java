package com.viralground.backend.notification;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.service.EmailService;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxDispatcher {

    private final NotificationOutboxRepository repository;
    private final EmailService emailService;
    private final Clock clock;
    private final PreproductionScheduledMutationGuard scheduledMutationGuard;

    @Value("${app.scheduling.enabled:false}")
    private boolean schedulingEnabled;

    @Value("${notification.outbox.enabled:true}")
    private boolean outboxEnabled;

    @Value("${notification.outbox.dispatch-enabled:false}")
    private boolean dispatchEnabled;

    @Value("${notification.outbox.batch-size:20}")
    private int batchSize;

    @Value("${notification.outbox.max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${notification.outbox.fixed-delay:30s}")
    @Transactional
    public void dispatchDue() {
        if (!schedulingEnabled || !outboxEnabled || !dispatchEnabled) return;
        scheduledMutationGuard.requireSafeForEmailDelivery();
        Instant now = clock.instant();
        for (NotificationOutbox notification : repository.findDueForUpdate(now, boundedBatchSize())) {
            try {
                String providerMessageId = emailService.deliverOutbox(
                        notification.getRecipient(), notification.getSubject(), notification.getHtmlBody(),
                        notification.getIdempotencyKey());
                notification.markSent(providerMessageId, clock.instant());
                log.info("event=notification_outbox_sent outboxId={} attempts={} providerMessageId={}",
                        notification.getId(), notification.getAttempts(), providerMessageId);
            } catch (RuntimeException deliveryFailure) {
                Instant failureTime = clock.instant();
                String safeCode = deliveryFailure.getClass().getSimpleName();
                notification.markFailed(safeCode, failureTime.plus(retryDelay(notification.getAttempts() + 1)),
                        boundedMaxAttempts(), failureTime);
                IllegalStateException safeSignal = new IllegalStateException(
                        "notification_outbox_delivery_failed id=" + notification.getId()
                                + " attempt=" + notification.getAttempts());
                Sentry.captureException(safeSignal);
                log.atError()
                        .addKeyValue("event", notification.getStatus() == NotificationOutboxStatus.DEAD_LETTER
                                ? "notification_outbox_dead_letter" : "notification_outbox_retry_scheduled")
                        .addKeyValue("outboxId", notification.getId())
                        .addKeyValue("attempts", notification.getAttempts())
                        .addKeyValue("errorType", safeCode)
                        .log("Notification delivery failed");
            }
        }
    }

    private Duration retryDelay(int failedAttempt) {
        long multiplier = 1L << Math.min(Math.max(failedAttempt - 1, 0), 7);
        return Duration.ofSeconds(Math.min(3600, 30L * multiplier));
    }

    private int boundedBatchSize() {
        return Math.max(1, Math.min(batchSize, 100));
    }

    private int boundedMaxAttempts() {
        return Math.max(1, Math.min(maxAttempts, 20));
    }
}
