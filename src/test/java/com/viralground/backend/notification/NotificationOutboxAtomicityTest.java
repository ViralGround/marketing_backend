package com.viralground.backend.notification;

import com.viralground.backend.config.PostgresTransactionAdvisoryLock;
import com.viralground.backend.repository.EmailVerificationCodeRepository;
import com.viralground.backend.service.EmailVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "email.mock=false",
        "email.delivery-mode=allowlist",
        "email.allowed-recipients=rollback@example.test",
        "notification.outbox.enabled=true",
        "notification.outbox.dispatch-enabled=false"
})
@ActiveProfiles("test")
class NotificationOutboxAtomicityTest {

    private static final String RECIPIENT = "rollback@example.test";

    @Autowired EmailVerificationService verificationService;
    @Autowired EmailVerificationCodeRepository codeRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PostgresTransactionAdvisoryLock transactionLock;

    @BeforeEach
    void cleanProbeRows() {
        codeRepository.deleteAll();
        outboxRepository.deleteAll();
    }

    @Test
    void verificationCodeAndOutboxCommitTogether() {
        verificationService.requestCode(RECIPIENT);

        assertThat(codeRepository.findByEmail(RECIPIENT)).isPresent();
        assertThat(outboxRepository.findAll()).singleElement().satisfies(outbox -> {
            assertThat(outbox.getNotificationKind()).isEqualTo("EMAIL_VERIFICATION_CODE");
            assertThat(outbox.getRecipient()).isEqualTo(RECIPIENT);
            assertThat(outbox.getIdempotencyKey()).startsWith("vg-outbox-")
                    .doesNotContain(RECIPIENT);
            assertThat(outbox.getProviderMessageId()).isNull();
        });
    }

    @Test
    void outerRollbackRemovesBothVerificationCodeAndOutbox() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            verificationService.requestCode(RECIPIENT);
            throw new IllegalStateException("force business rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(codeRepository.findByEmail(RECIPIENT)).isEmpty();
        assertThat(outboxRepository.findAll())
                .noneMatch(outbox -> RECIPIENT.equals(outbox.getRecipient()));
    }

    @Test
    void reissuingCodeSupersedesAndRedactsTheUndispatchedMessage() {
        verificationService.requestCode(RECIPIENT);
        verificationService.requestCode(RECIPIENT);

        assertThat(outboxRepository.findAll())
                .filteredOn(outbox -> outbox.getStatus() == NotificationOutboxStatus.SUPERSEDED)
                .singleElement()
                .satisfies(outbox -> {
                    assertThat(outbox.getRecipient()).isEqualTo("[REDACTED]");
                    assertThat(outbox.getSubject()).isEqualTo("[REDACTED]");
                    assertThat(outbox.getHtmlBody()).isEqualTo("[SUPERSEDED]");
                });
        assertThat(outboxRepository.findAll())
                .filteredOn(outbox -> outbox.getStatus() == NotificationOutboxStatus.PENDING)
                .singleElement();
    }
}
