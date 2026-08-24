package com.viralground.backend.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class PostgresTransactionAdvisoryLockIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres:16.4-alpine@sha256:5660c2cbfea50c7a9127d17dc4e48543eedd3d7a41a595a2dfa572471e37e64c")
            .withDatabaseName("viralground_advisory_lock_test")
            .withUsername("viralground_test")
            .withPassword("viralground_test_password");

    @Test
    void sameKeyBlocksAcrossConnectionsUntilCommit() throws Exception {
        assertSameKeyBlocksUntilTransactionEnds(false);
    }

    @Test
    void sameKeyBlocksAcrossConnectionsUntilRollback() throws Exception {
        assertSameKeyBlocksUntilTransactionEnds(true);
    }

    private void assertSameKeyBlocksUntilTransactionEnds(boolean rollback) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        TransactionTemplate firstTransaction = readCommitted(transactionManager);
        TransactionTemplate secondTransaction = readCommitted(transactionManager);
        PostgresTransactionAdvisoryLock lock =
                new PostgresTransactionAdvisoryLock(new JdbcTemplate(dataSource));

        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                try {
                    firstTransaction.executeWithoutResult(status -> {
                        lock.lock(PostgresTransactionAdvisoryLock.Scope.EMAIL_VERIFICATION_REQUEST,
                                "person@example.com");
                        firstAcquired.countDown();
                        await(releaseFirst);
                        if (rollback) throw new ExpectedRollback();
                    });
                } catch (ExpectedRollback expected) {
                    // The assertion is that PostgreSQL releases the xact lock on rollback.
                }
            });
            assertThat(firstAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> secondTransaction.executeWithoutResult(status -> {
                lock.lock(PostgresTransactionAdvisoryLock.Scope.EMAIL_VERIFICATION_REQUEST,
                        "person@example.com");
                secondAcquired.countDown();
            }));
            awaitAdvisoryWaiter(new JdbcTemplate(dataSource), Duration.ofSeconds(5));
            assertThat(secondAcquired.getCount()).isOne();

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThat(secondAcquired.getCount()).isZero();
        }
    }

    private static void awaitAdvisoryWaiter(JdbcTemplate jdbcTemplate, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Long waiters = jdbcTemplate.queryForObject("""
                    SELECT count(*)
                    FROM pg_catalog.pg_locks
                    WHERE locktype = 'advisory' AND NOT granted
                    """, Long.class);
            if (waiters != null && waiters > 0) return;
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("second connection did not wait on the PostgreSQL advisory lock");
    }

    private static TransactionTemplate readCommitted(
            DataSourceTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
        return transaction;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for advisory-lock probe");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("advisory-lock probe interrupted", interrupted);
        }
    }

    private static final class ExpectedRollback extends RuntimeException {
    }
}
