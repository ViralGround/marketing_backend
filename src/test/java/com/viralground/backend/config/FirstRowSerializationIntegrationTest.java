package com.viralground.backend.config;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import com.viralground.backend.notification.NotificationOutbox;
import com.viralground.backend.notification.NotificationOutboxRepository;
import com.viralground.backend.notification.NotificationOutboxService;
import com.viralground.backend.notification.NotificationOutboxStatus;
import com.viralground.backend.repository.EmailVerificationCodeRepository;
import com.viralground.backend.repository.MemberRepository;
import com.viralground.backend.repository.PasswordResetCodeRepository;
import com.viralground.backend.repository.RefreshTokenRepository;
import com.viralground.backend.service.EmailService;
import com.viralground.backend.service.EmailVerificationService;
import com.viralground.backend.service.JwtService;
import com.viralground.backend.service.PasswordResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the empty-result race is closed through the real Spring/JPA service
 * boundary, not only through the advisory-lock primitive. No dispatcher or
 * external email delivery is enabled.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "email.mock=false",
        "email.delivery-mode=allowlist",
        "email.allowed-recipients=verification-race@example.test,reset-race@example.test",
        "resend.api-key=integration-test-placeholder",
        "resend.from=integration@example.test",
        "notification.outbox.enabled=true",
        "notification.outbox.dispatch-enabled=false",
        "features.payments.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        PostgresTransactionAdvisoryLock.class,
        NotificationOutboxService.class,
        EmailService.class,
        EmailVerificationService.class,
        PasswordResetService.class,
        FirstRowSerializationIntegrationTest.TestBeans.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FirstRowSerializationIntegrationTest {

    private static final String POSTGRES_IMAGE =
            "postgres:16.4-alpine@sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("viralground_first_row_test")
            .withUsername("viralground_test")
            .withPassword("viralground_test_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired EmailVerificationService emailVerificationService;
    @Autowired PasswordResetService passwordResetService;
    @Autowired NotificationOutboxService outboxService;
    @Autowired PostgresTransactionAdvisoryLock transactionLock;
    @Autowired EmailVerificationCodeRepository verificationCodeRepository;
    @Autowired PasswordResetCodeRepository passwordResetCodeRepository;
    @Autowired NotificationOutboxRepository outboxRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean JwtService jwtService;

    @BeforeEach
    void cleanRows() {
        outboxRepository.deleteAllInBatch();
        verificationCodeRepository.deleteAllInBatch();
        passwordResetCodeRepository.deleteAllInBatch();
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void concurrentFirstVerificationRequestsLeaveOneCodeAndOnePendingMessage() throws Exception {
        String email = "verification-race@example.test";

        runTwoCallsBehindHeldLock(
                PostgresTransactionAdvisoryLock.Scope.EMAIL_VERIFICATION_REQUEST,
                email,
                () -> emailVerificationService.requestCode("  VERIFICATION-RACE@example.test "),
                () -> emailVerificationService.requestCode(email));

        assertThat(verificationCodeRepository.findAll())
                .singleElement()
                .satisfies(code -> assertThat(code.getEmail()).isEqualTo(email));
        assertReplaceableOutboxState("EMAIL_VERIFICATION_CODE", email);
    }

    @Test
    void concurrentFirstPasswordResetRequestsLeaveOneCodeAndOnePendingMessage() throws Exception {
        String email = "reset-race@example.test";
        memberRepository.saveAndFlush(Member.builder()
                .email(email)
                .password("not-used-by-this-test")
                .name("Reset race probe")
                .role(Role.CREATOR)
                .status(MemberStatus.APPROVED)
                .emailVerified(true)
                .build());

        runTwoCallsBehindHeldLock(
                PostgresTransactionAdvisoryLock.Scope.PASSWORD_RESET_REQUEST,
                email,
                () -> passwordResetService.requestCode(" RESET-RACE@example.test "),
                () -> passwordResetService.requestCode(email));

        assertThat(passwordResetCodeRepository.findAll())
                .singleElement()
                .satisfies(code -> assertThat(code.getEmail()).isEqualTo(email));
        assertReplaceableOutboxState("PASSWORD_RESET_CODE", email);
    }

    @Test
    void concurrentFirstOutboxSupersessionLeavesOnePendingAndOneRedactedRow() throws Exception {
        String kind = "DIRECT_RACE_PROBE";
        String recipient = "direct-race@example.test";
        String logicalIdentity = kind + "\0" + recipient;

        runTwoCallsBehindHeldLock(
                PostgresTransactionAdvisoryLock.Scope.NOTIFICATION_OUTBOX,
                logicalIdentity,
                () -> outboxService.supersedePendingAndEnqueue(
                        " DIRECT_RACE_PROBE ", NotificationOutboxService.newIdempotencyKey(),
                        " DIRECT-RACE@example.test ", "first", "first-body"),
                () -> outboxService.supersedePendingAndEnqueue(
                        kind, NotificationOutboxService.newIdempotencyKey(),
                        recipient, "second", "second-body"));

        assertReplaceableOutboxState(kind, recipient);
    }

    private void runTwoCallsBehindHeldLock(
            PostgresTransactionAdvisoryLock.Scope scope,
            String identity,
            Runnable firstCall,
            Runnable secondCall) throws Exception {
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        TransactionTemplate holderTransaction = new TransactionTemplate(transactionManager);
        holderTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        holderTransaction.setTimeout(10);

        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> holderTransaction.executeWithoutResult(status -> {
                transactionLock.lock(scope, identity);
                holderAcquired.countDown();
                await(releaseHolder, "release held advisory lock");
            }));
            assertThat(holderAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> first = executor.submit(firstCall);
            Future<?> second = executor.submit(secondCall);

            // A database-side observation makes this deterministic: both real
            // service calls must reach PostgreSQL and wait on the held key.
            awaitAdvisoryWaiters(2, Duration.ofSeconds(5));
            assertThat(first.isDone()).isFalse();
            assertThat(second.isDone()).isFalse();

            releaseHolder.countDown();
            holder.get(5, TimeUnit.SECONDS);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
        }
    }

    private void awaitAdvisoryWaiters(int expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Long waiters = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM pg_catalog.pg_locks
                    WHERE locktype = 'advisory' AND NOT granted
                    """, Long.class);
            if (waiters != null && waiters >= expected) return;
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("real service calls did not reach the PostgreSQL advisory lock");
    }

    private void assertReplaceableOutboxState(String kind, String recipient) {
        List<NotificationOutbox> messages = outboxRepository.findAll().stream()
                .filter(message -> kind.equals(message.getNotificationKind()))
                .toList();
        assertThat(messages).hasSize(2);
        assertThat(messages)
                .filteredOn(message -> message.getStatus() == NotificationOutboxStatus.PENDING)
                .singleElement()
                .satisfies(message -> assertThat(message.getRecipient()).isEqualTo(recipient));
        assertThat(messages)
                .filteredOn(message -> message.getStatus() == NotificationOutboxStatus.SUPERSEDED)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getRecipient()).isEqualTo("[REDACTED]");
                    assertThat(message.getSubject()).isEqualTo("[REDACTED]");
                    assertThat(message.getHtmlBody()).isEqualTo("[SUPERSEDED]");
                });
    }

    private static void await(CountDownLatch latch, String purpose) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to " + purpose);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to " + purpose, interrupted);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }
}
