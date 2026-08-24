package com.viralground.backend.config;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSafetyStateTest {
    private static final String EXPECTED_SEAL = "a".repeat(64);
    private static final String EXPECTED_FINGERPRINT =
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb";
    private static final String E2E_BEFORE_SEAL = "c".repeat(64);
    private static final String SOURCE_SNAPSHOT = "provider-snapshot-20260822";
    private static final String E2E_BEFORE_FINGERPRINT =
            "52b6419d27bd7f547cee3b92f8c17a908b8a49601ecbec161e5030de1dfe9e0a";

    @Test
    void operatorConfigurationAloneNeverCountsAsCloneProof() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        RuntimeSafetyState state = new RuntimeSafetyState(dataSource);

        assertThat(state.currentCloneVerification())
                .isEqualTo(new RuntimeSafetyState.CloneVerification(
                        "unverified", false, false, false, "", false, ""));
        verify(dataSource, never()).getConnection();
    }

    @Test
    void refusesToRecordAnInvalidSourceSnapshotBinding() {
        RuntimeSafetyState state = new RuntimeSafetyState(mock(DataSource.class));

        assertThatThrownBy(() -> state.recordCompletedClone(
                "sanitized", "sentinel-1", "", "release-1",
                "viralground_release_staging", EXPECTED_SEAL, E2E_BEFORE_SEAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source snapshot");
    }

    @Test
    void rechecksReleaseBoundCompletedSentinelAndBaselineAgainstDatabase() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = completedCloneConnection();
        when(dataSource.getConnection()).thenReturn(connection);
        RuntimeSafetyState state = new RuntimeSafetyState(dataSource);

        state.recordCompletedClone(
                "sanitized", "sentinel-1", SOURCE_SNAPSHOT, "release-1",
                "viralground_release_staging", EXPECTED_SEAL, E2E_BEFORE_SEAL);

        assertThat(state.currentCloneVerification())
                .isEqualTo(new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, true, true, EXPECTED_FINGERPRINT,
                        true, E2E_BEFORE_FINGERPRINT));
        verify(connection).setReadOnly(true);
    }

    @Test
    void currentDatabaseMismatchFailsClosedWithoutReadingSentinel() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT current_database()"))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn("unexpected_database");
        RuntimeSafetyState state = new RuntimeSafetyState(dataSource);
        state.recordCompletedClone(
                "sanitized", "sentinel-1", SOURCE_SNAPSHOT, "release-1",
                "viralground_release_staging", EXPECTED_SEAL, E2E_BEFORE_SEAL);

        assertThat(state.currentCloneVerification())
                .isEqualTo(new RuntimeSafetyState.CloneVerification(
                        "sanitized", false, false, false, "", false, ""));
        verify(connection, never()).prepareStatement(
                org.mockito.ArgumentMatchers.contains("clone_sentinel"));
    }

    @Test
    void changedLiveEvidenceSealFailsClosedAndFingerprintsOnlyTheLiveSeal() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = completedCloneConnection("b".repeat(64));
        when(dataSource.getConnection()).thenReturn(connection);
        RuntimeSafetyState state = new RuntimeSafetyState(dataSource);
        state.recordCompletedClone(
                "sanitized", "sentinel-1", SOURCE_SNAPSHOT, "release-1",
                "viralground_release_staging", EXPECTED_SEAL, E2E_BEFORE_SEAL);

        assertThat(state.currentCloneVerification())
                .isEqualTo(new RuntimeSafetyState.CloneVerification(
                        "sanitized", true, false, false,
                        "a0fab1377f49a759b57f63318262ebe89fabfc990e8e93ceac2984561482b9d4",
                        true, E2E_BEFORE_FINGERPRINT));
    }

    @Test
    void expiredDestroyedOrReleaseChangedSentinelFailsClosedOnTheNextRead() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(result);
            if (sql.equals("SELECT current_database()")) {
                when(result.next()).thenReturn(true, false);
                when(result.getString(1)).thenReturn("viralground_release_staging");
            } else if (sql.contains("preprod_guard.clone_sentinel")) {
                when(result.next()).thenReturn(false);
            } else {
                throw new AssertionError("unexpected SQL: " + sql);
            }
            return statement;
        });
        when(dataSource.getConnection()).thenReturn(connection);
        RuntimeSafetyState state = new RuntimeSafetyState(dataSource);
        state.recordCompletedClone(
                "sanitized", "sentinel-1", SOURCE_SNAPSHOT, "release-1",
                "viralground_release_staging", EXPECTED_SEAL, E2E_BEFORE_SEAL);

        assertThat(state.currentCloneVerification())
                .isEqualTo(new RuntimeSafetyState.CloneVerification(
                        "sanitized", false, false, false, "", false, ""));
    }

    @Test
    void changedE2eBeforeSealFailsTheIndependentLiveAttestation() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = completedCloneConnection(
                EXPECTED_SEAL, "d".repeat(64));
        when(dataSource.getConnection()).thenReturn(connection);
        RuntimeSafetyState state = new RuntimeSafetyState(dataSource);
        state.recordCompletedClone(
                "sanitized", "sentinel-1", SOURCE_SNAPSHOT, "release-1",
                "viralground_release_staging", EXPECTED_SEAL, E2E_BEFORE_SEAL);

        RuntimeSafetyState.CloneVerification verification =
                state.currentCloneVerification();

        assertThat(verification.migrationEvidenceComplete()).isTrue();
        assertThat(verification.evidenceSealMatched()).isTrue();
        assertThat(verification.e2eBeforeEvidenceSealMatched()).isFalse();
        assertThat(verification.e2eBeforeEvidenceSealFingerprint())
                .matches("^[0-9a-f]{64}$")
                .isNotEqualTo(E2E_BEFORE_FINGERPRINT);
    }

    private static Connection completedCloneConnection() throws Exception {
        return completedCloneConnection(EXPECTED_SEAL, E2E_BEFORE_SEAL);
    }

    private static Connection completedCloneConnection(String liveSeal) throws Exception {
        return completedCloneConnection(liveSeal, E2E_BEFORE_SEAL);
    }

    private static Connection completedCloneConnection(
            String liveSeal, String liveE2eBeforeSeal) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class);
            when(statement.executeQuery()).thenReturn(result);
            if (sql.equals("SELECT current_database()")) {
                when(result.next()).thenReturn(true, false);
                when(result.getString(1)).thenReturn("viralground_release_staging");
            } else if (sql.contains("preprod_guard.clone_sentinel")) {
                AtomicInteger nextCalls = new AtomicInteger();
                AtomicBoolean positionedOnRow = new AtomicBoolean();
                when(result.next()).thenAnswer(ignored -> {
                    boolean firstRow = nextCalls.getAndIncrement() == 0;
                    positionedOnRow.set(firstRow);
                    return firstRow;
                });
                when(result.getBoolean(1)).thenAnswer(ignored ->
                        readableSentinelValue(positionedOnRow, true));
                when(result.getBoolean(2)).thenAnswer(ignored ->
                        readableSentinelValue(positionedOnRow, true));
                when(result.getString(3)).thenAnswer(ignored ->
                        readableSentinelValue(positionedOnRow, liveSeal));
                when(result.getBoolean(4)).thenAnswer(ignored ->
                        readableSentinelValue(positionedOnRow, true));
                when(result.getString(5)).thenAnswer(ignored ->
                        readableSentinelValue(positionedOnRow, liveE2eBeforeSeal));
            } else if (sql.contains("flyway_schema_history")) {
                when(result.next()).thenReturn(true, false);
                when(result.getLong(1)).thenReturn(1L);
            } else {
                throw new AssertionError("unexpected SQL: " + sql);
            }
            return statement;
        });
        return connection;
    }

    private static <T> T readableSentinelValue(
            AtomicBoolean positionedOnRow, T value) throws SQLException {
        if (!positionedOnRow.get()) {
            throw new SQLException("ResultSet cursor is not positioned on the sentinel row");
        }
        return value;
    }
}
