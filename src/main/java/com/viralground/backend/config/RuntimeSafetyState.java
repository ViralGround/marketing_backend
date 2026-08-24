package com.viralground.backend.config;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Keeps the non-secret clone binding proven during protected startup and
 * re-checks its release sentinel when the runtime safety contract is read.
 *
 * <p>Configuration values alone must never be treated as proof that a process
 * is connected to the approved sanitized clone. The binding is recorded only
 * after {@link GuardedFlywayMigrationConfiguration} has validated the target,
 * completed sentinel and V1 baseline. Each actuator read then confirms that
 * the same database still contains the matching live sentinel.</p>
 */
@Component
public final class RuntimeSafetyState {
    private static final Set<String> CLONE_KINDS = Set.of("exact", "sanitized");
    private static final Pattern SHA256_HEX = Pattern.compile("(?i)^[0-9a-f]{64}$");
    private static final Pattern SOURCE_SNAPSHOT_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,255}$");

    private final DataSource dataSource;
    private final AtomicReference<CloneBinding> completedClone = new AtomicReference<>();

    public RuntimeSafetyState(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void recordCompletedClone(
            String cloneKind,
            String sentinelId,
            String sourceSnapshotId,
            String releaseId,
            String database,
            String evidenceSealSha256,
            String e2eBeforeEvidenceSealSha256) {
        if (!CLONE_KINDS.contains(cloneKind)) {
            throw new IllegalArgumentException("unsupported clone kind");
        }
        if (sourceSnapshotId == null
                || !SOURCE_SNAPSHOT_ID.matcher(sourceSnapshotId).matches()) {
            throw new IllegalArgumentException("source snapshot identifier is invalid");
        }
        if (evidenceSealSha256 == null
                || !SHA256_HEX.matcher(evidenceSealSha256).matches()) {
            throw new IllegalArgumentException("evidence seal must be a SHA-256 hex value");
        }
        String e2eBeforeSeal = e2eBeforeEvidenceSealSha256 == null
                ? "" : e2eBeforeEvidenceSealSha256;
        if (!e2eBeforeSeal.isEmpty()
                && !SHA256_HEX.matcher(e2eBeforeSeal).matches()) {
            throw new IllegalArgumentException(
                    "sanitized E2E-before evidence seal must be a SHA-256 hex value");
        }
        completedClone.set(new CloneBinding(
                cloneKind,
                sentinelId,
                sourceSnapshotId,
                releaseId,
                database,
                evidenceSealSha256.toLowerCase(Locale.ROOT),
                e2eBeforeSeal.toLowerCase(Locale.ROOT)));
    }

    public CloneVerification currentCloneVerification() {
        CloneBinding binding = completedClone.get();
        if (binding == null) {
            return CloneVerification.unverified();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            if (!binding.database().equals(currentDatabase(connection))) {
                return CloneVerification.failed(binding.cloneKind());
            }

            SentinelVerification sentinel = readSentinel(connection, binding);
            String liveEvidenceSeal = normalizeEvidenceSeal(
                    sentinel.evidenceSealSha256());
            boolean evidenceSealMatched = sentinel.releaseIdMatched()
                    && !liveEvidenceSeal.isEmpty()
                    && binding.evidenceSealSha256().equals(liveEvidenceSeal);
            String liveE2eBeforeEvidenceSeal = normalizeEvidenceSeal(
                    sentinel.e2eBeforeEvidenceSealSha256());
            boolean e2eBeforeEvidenceSealMatched = sentinel.releaseIdMatched()
                    && sentinel.e2eBeforeRecorded()
                    && !binding.e2eBeforeEvidenceSealSha256().isEmpty()
                    && binding.e2eBeforeEvidenceSealSha256().equals(
                    liveE2eBeforeEvidenceSeal);
            boolean migrationEvidenceComplete = sentinel.releaseIdMatched()
                    && sentinel.baselineStarted()
                    && sentinel.baselineCompleted()
                    && evidenceSealMatched
                    && successfulV1BaselineCount(connection) == 1;
            return new CloneVerification(
                    binding.cloneKind(),
                    sentinel.releaseIdMatched(),
                    migrationEvidenceComplete,
                    evidenceSealMatched,
                    evidenceSealFingerprint(liveEvidenceSeal),
                    e2eBeforeEvidenceSealMatched,
                    evidenceSealFingerprint(liveE2eBeforeEvidenceSeal));
        } catch (SQLException | RuntimeException unavailable) {
            return CloneVerification.failed(binding.cloneKind());
        }
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_database()");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) return "";
            return result.getString(1);
        }
    }

    private static SentinelVerification readSentinel(
            Connection connection, CloneBinding binding) throws SQLException {
        String sql = """
                SELECT baseline_started_at IS NOT NULL,
                       baseline_completed_at IS NOT NULL,
                       evidence_seal_sha256,
                       e2e_before_recorded_at IS NOT NULL,
                       e2e_before_evidence_seal_sha256
                FROM preprod_guard.clone_sentinel
                WHERE sentinel_id = ?
                  AND clone_kind = ?
                  AND source_snapshot_id = ?
                  AND release_id = ?
                  AND destroyed_at IS NULL
                  AND created_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                  AND expires_at > CURRENT_TIMESTAMP
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, binding.sentinelId());
            statement.setString(2, binding.cloneKind());
            statement.setString(3, binding.sourceSnapshotId());
            statement.setString(4, binding.releaseId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return SentinelVerification.missing();
                boolean started = result.getBoolean(1);
                boolean completed = result.getBoolean(2);
                String evidenceSealSha256 = result.getString(3);
                boolean e2eBeforeRecorded = result.getBoolean(4);
                String e2eBeforeEvidenceSealSha256 = result.getString(5);
                if (result.next()) return SentinelVerification.missing();
                return new SentinelVerification(
                        true,
                        started,
                        completed,
                        evidenceSealSha256,
                        e2eBeforeRecorded,
                        e2eBeforeEvidenceSealSha256);
            }
        }
    }

    private static int successfulV1BaselineCount(Connection connection) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '1' AND type = 'BASELINE' AND success = TRUE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) return 0;
            return Math.toIntExact(result.getLong(1));
        }
    }

    private record CloneBinding(
            String cloneKind,
            String sentinelId,
            String sourceSnapshotId,
            String releaseId,
            String database,
            String evidenceSealSha256,
            String e2eBeforeEvidenceSealSha256) {
    }

    private record SentinelVerification(
            boolean releaseIdMatched,
            boolean baselineStarted,
            boolean baselineCompleted,
            String evidenceSealSha256,
            boolean e2eBeforeRecorded,
            String e2eBeforeEvidenceSealSha256) {
        private static SentinelVerification missing() {
            return new SentinelVerification(
                    false, false, false, null, false, null);
        }
    }

    public record CloneVerification(
            String cloneKind,
            boolean releaseIdMatched,
            boolean migrationEvidenceComplete,
            boolean evidenceSealMatched,
            String evidenceSealFingerprint,
            boolean e2eBeforeEvidenceSealMatched,
            String e2eBeforeEvidenceSealFingerprint) {
        private static CloneVerification unverified() {
            return new CloneVerification(
                    "unverified", false, false, false, "", false, "");
        }

        private static CloneVerification failed(String cloneKind) {
            return new CloneVerification(
                    cloneKind, false, false, false, "", false, "");
        }
    }

    private static String normalizeEvidenceSeal(String evidenceSealSha256) {
        if (evidenceSealSha256 == null
                || !SHA256_HEX.matcher(evidenceSealSha256).matches()) {
            return "";
        }
        return evidenceSealSha256.toLowerCase(Locale.ROOT);
    }

    private static String evidenceSealFingerprint(String normalizedEvidenceSeal) {
        if (normalizedEvidenceSeal.isEmpty()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    normalizedEvidenceSeal.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
