package com.viralground.backend.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;

/**
 * Serializes first-row creation across every application replica without
 * persisting an email address, recipient, or even a reversible lookup key.
 */
@Component
public final class PostgresTransactionAdvisoryLock {

    private static final String KEY_DOMAIN = "viralground-tx-lock-v1\0";

    public enum Scope {
        EMAIL_VERIFICATION_REQUEST,
        PASSWORD_RESET_REQUEST,
        NOTIFICATION_OUTBOX
    }

    private final JdbcTemplate jdbcTemplate;

    public PostgresTransactionAdvisoryLock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The lock is owned by, and automatically released with, the current DB transaction. */
    public void lock(Scope scope, String identity) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PostgreSQL advisory lock requires an active transaction");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException("first-row serialization requires READ_COMMITTED isolation");
        }
        Long acquired = jdbcTemplate.queryForObject(
                "SELECT 1 FROM pg_catalog.pg_advisory_xact_lock(?)",
                Long.class,
                lockKey(scope, identity));
        if (!Long.valueOf(1L).equals(acquired)) {
            throw new IllegalStateException("PostgreSQL advisory transaction lock was not acquired");
        }
    }

    /** Package-visible only for a fixed golden-vector regression test. */
    static long lockKey(Scope scope, String identity) {
        if (scope == null || identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("advisory lock scope and identity are required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((KEY_DOMAIN + scope.name() + "\0" + identity)
                    .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(bytes).getLong();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
