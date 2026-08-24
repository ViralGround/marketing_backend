package com.viralground.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PostgresTransactionAdvisoryLockTest {

    @Test
    void stableDomainSeparatedKeyNeverContainsOrPersistsTheIdentity() {
        long verification = PostgresTransactionAdvisoryLock.lockKey(
                PostgresTransactionAdvisoryLock.Scope.EMAIL_VERIFICATION_REQUEST,
                "person@example.com");
        long reset = PostgresTransactionAdvisoryLock.lockKey(
                PostgresTransactionAdvisoryLock.Scope.PASSWORD_RESET_REQUEST,
                "person@example.com");
        long outbox = PostgresTransactionAdvisoryLock.lockKey(
                PostgresTransactionAdvisoryLock.Scope.NOTIFICATION_OUTBOX,
                "EMAIL_VERIFICATION_CODE\0person@example.com");

        assertThat(verification).isEqualTo(1443514359917106940L);
        assertThat(reset).isNotEqualTo(verification);
        assertThat(outbox).isNotIn(verification, reset);
    }

    @Test
    void refusesMissingScopeOrIdentity() {
        assertThatThrownBy(() -> PostgresTransactionAdvisoryLock.lockKey(
                null, "person@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PostgresTransactionAdvisoryLock.lockKey(
                PostgresTransactionAdvisoryLock.Scope.NOTIFICATION_OUTBOX, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToFallBackToAConnectionScopedLockWithoutAnActiveTransaction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PostgresTransactionAdvisoryLock lock =
                new PostgresTransactionAdvisoryLock(jdbcTemplate);

        assertThatThrownBy(() -> lock.lock(
                PostgresTransactionAdvisoryLock.Scope.EMAIL_VERIFICATION_REQUEST,
                "person@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
        verifyNoInteractions(jdbcTemplate);
    }
}
