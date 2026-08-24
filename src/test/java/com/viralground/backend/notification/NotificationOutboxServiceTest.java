package com.viralground.backend.notification;

import com.viralground.backend.config.PostgresTransactionAdvisoryLock;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutboxServiceTest {

    @Test
    void firstRowLockPrecedesPendingRowLockAndCanonicalInsert() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        PostgresTransactionAdvisoryLock transactionLock =
                mock(PostgresTransactionAdvisoryLock.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T00:00:00Z"), ZoneOffset.UTC);
        NotificationOutboxService service =
                new NotificationOutboxService(repository, clock, transactionLock);
        ReflectionTestUtils.setField(service, "outboxEnabled", true);
        when(repository.findByNotificationKindAndRecipientAndStatusOrderByCreatedAtAsc(
                "EMAIL_VERIFICATION_CODE", "person@example.com",
                NotificationOutboxStatus.PENDING)).thenReturn(List.of());

        service.supersedePendingAndEnqueue(
                " EMAIL_VERIFICATION_CODE ", "opaque-key", "Person@Example.com",
                "subject", "body");

        InOrder order = inOrder(transactionLock, repository);
        order.verify(transactionLock).lock(
                PostgresTransactionAdvisoryLock.Scope.NOTIFICATION_OUTBOX,
                "EMAIL_VERIFICATION_CODE\0person@example.com");
        order.verify(repository).findByNotificationKindAndRecipientAndStatusOrderByCreatedAtAsc(
                "EMAIL_VERIFICATION_CODE", "person@example.com",
                NotificationOutboxStatus.PENDING);
        ArgumentCaptor<NotificationOutbox> saved =
                ArgumentCaptor.forClass(NotificationOutbox.class);
        order.verify(repository).save(saved.capture());
        assertThat(saved.getValue().getNotificationKind()).isEqualTo("EMAIL_VERIFICATION_CODE");
        assertThat(saved.getValue().getRecipient()).isEqualTo("person@example.com");
        verify(transactionLock).lock(
                PostgresTransactionAdvisoryLock.Scope.NOTIFICATION_OUTBOX,
                "EMAIL_VERIFICATION_CODE\0person@example.com");
    }
}
