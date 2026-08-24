package com.viralground.backend.notification;

import com.viralground.backend.config.PreproductionScheduledMutationGuard;
import com.viralground.backend.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-22T01:00:00Z");

    @Test
    void successfulDeliveryMarksSentAndRedactsBody() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        EmailService emailService = mock(EmailService.class);
        NotificationOutbox notification = new NotificationOutbox(
                "APPLICATION_RESULT", "vg-outbox-test-success",
                "qa@example.test", "subject", "<p>private</p>", NOW);
        when(repository.findDueForUpdate(any(Instant.class), anyInt())).thenReturn(List.of(notification));
        when(emailService.deliverOutbox(any(), any(), any(), any())).thenReturn("resend-message-123");
        NotificationOutboxDispatcher dispatcher = dispatcher(repository, emailService, 5);

        dispatcher.dispatchDue();

        verify(emailService).deliverOutbox(
                "qa@example.test", "subject", "<p>private</p>", notification.getIdempotencyKey());
        assertThat(notification.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        assertThat(notification.getRecipient()).isEqualTo("[REDACTED]");
        assertThat(notification.getSubject()).isEqualTo("[REDACTED]");
        assertThat(notification.getHtmlBody()).isEqualTo("[DELIVERED]");
        assertThat(notification.getSentAt()).isEqualTo(NOW);
        assertThat(notification.getProviderMessageId()).isEqualTo("resend-message-123");
        assertThat(notification.getNotificationKind()).isEqualTo("APPLICATION_RESULT");
    }

    @Test
    void terminalFailureMovesToDeadLetterAndRedactsBody() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        EmailService emailService = mock(EmailService.class);
        NotificationOutbox notification = new NotificationOutbox(
                "APPLICATION_RESULT", "vg-outbox-test-failure",
                "qa@example.test", "subject", "<p>private</p>", NOW);
        when(repository.findDueForUpdate(any(Instant.class), anyInt())).thenReturn(List.of(notification));
        doThrow(new IllegalStateException("provider down"))
                .when(emailService).deliverOutbox(any(), any(), any(), any());
        NotificationOutboxDispatcher dispatcher = dispatcher(repository, emailService, 1);

        dispatcher.dispatchDue();

        assertThat(notification.getStatus()).isEqualTo(NotificationOutboxStatus.DEAD_LETTER);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getRecipient()).isEqualTo("[REDACTED]");
        assertThat(notification.getSubject()).isEqualTo("[REDACTED]");
        assertThat(notification.getHtmlBody()).isEqualTo("[REDACTED_AFTER_FAILURE]");
        assertThat(notification.getLastErrorCode()).isEqualTo("IllegalStateException");
        assertThat(notification.getProviderMessageId()).isNull();
        assertThat(notification.getIdempotencyKey()).startsWith("vg-outbox-");
    }

    @Test
    void transientFailureSchedulesRetryWithoutLeakingOrDiscardingTheMessage() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        EmailService emailService = mock(EmailService.class);
        NotificationOutbox notification = new NotificationOutbox(
                "PASSWORD_RESET_CODE", "vg-outbox-test-retry",
                "qa@example.test", "subject", "<p>private</p>", NOW);
        when(repository.findDueForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(notification));
        doThrow(new IllegalStateException("provider timeout"))
                .when(emailService).deliverOutbox(any(), any(), any(), any());

        dispatcher(repository, emailService, 5).dispatchDue();

        assertThat(notification.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(notification.getAttempts()).isEqualTo(1);
        assertThat(notification.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(notification.getLastErrorCode()).isEqualTo("IllegalStateException");
        assertThat(notification.getRecipient()).isEqualTo("qa@example.test");
        assertThat(notification.getHtmlBody()).isEqualTo("<p>private</p>");
    }

    @Test
    void dispatchUsesTheDedicatedEmailDeliverySafetyGate() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        EmailService emailService = mock(EmailService.class);
        PreproductionScheduledMutationGuard guard =
                mock(PreproductionScheduledMutationGuard.class);
        when(repository.findDueForUpdate(any(Instant.class), anyInt())).thenReturn(List.of());
        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(
                repository, emailService, Clock.fixed(NOW, ZoneOffset.UTC), guard);
        ReflectionTestUtils.setField(dispatcher, "schedulingEnabled", true);
        ReflectionTestUtils.setField(dispatcher, "outboxEnabled", true);
        ReflectionTestUtils.setField(dispatcher, "dispatchEnabled", true);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 20);
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", 5);

        dispatcher.dispatchDue();

        verify(guard).requireSafeForEmailDelivery();
        verify(guard, never()).requireSafe();
    }

    private NotificationOutboxDispatcher dispatcher(NotificationOutboxRepository repository,
                                                      EmailService emailService,
                                                      int maxAttempts) {
        NotificationOutboxDispatcher dispatcher = new NotificationOutboxDispatcher(
                repository, emailService, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PreproductionScheduledMutationGuard.class));
        ReflectionTestUtils.setField(dispatcher, "schedulingEnabled", true);
        ReflectionTestUtils.setField(dispatcher, "outboxEnabled", true);
        ReflectionTestUtils.setField(dispatcher, "dispatchEnabled", true);
        ReflectionTestUtils.setField(dispatcher, "batchSize", 20);
        ReflectionTestUtils.setField(dispatcher, "maxAttempts", maxAttempts);
        return dispatcher;
    }
}
