package com.viralground.backend.repository;

import com.viralground.backend.entity.ConnectionStatus;
import com.viralground.backend.entity.CreatorInstagramConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CreatorInstagramConnectionRepository
        extends JpaRepository<CreatorInstagramConnection, Integer> {

    Optional<CreatorInstagramConnection> findByCreatorId(Integer creatorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT connection FROM CreatorInstagramConnection connection "
            + "WHERE connection.creatorId = :creatorId")
    Optional<CreatorInstagramConnection> findByCreatorIdForUpdate(
            @Param("creatorId") Integer creatorId);

    Optional<CreatorInstagramConnection> findByProviderAccountId(String providerAccountId);

    boolean existsByProviderAccountIdAndCreatorIdNot(String providerAccountId, Integer creatorId);

    List<CreatorInstagramConnection> findByStatus(ConnectionStatus status);

    /** Update sync metadata only; never merge a detached token-bearing entity. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CreatorInstagramConnection connection SET "
            + "connection.lastSyncedAt = :syncedAt, connection.lastError = NULL, "
            + "connection.encryptedAccessToken = :encryptedAccessToken, "
            + "connection.accessTokenExpiresAt = :accessTokenExpiresAt, "
            + "connection.tokenRefreshedAt = :tokenRefreshedAt, "
            + "connection.updatedAt = :updatedAt "
            + "WHERE connection.id = :id AND connection.status = :connected "
            + "AND (connection.encryptedAccessToken = :expectedEncryptedAccessToken "
            + "OR (connection.encryptedAccessToken IS NULL "
            + "AND :expectedEncryptedAccessToken IS NULL))")
    int markSyncSucceededIfConnected(
            @Param("id") Integer id,
            @Param("connected") ConnectionStatus connected,
            @Param("expectedEncryptedAccessToken") String expectedEncryptedAccessToken,
            @Param("encryptedAccessToken") String encryptedAccessToken,
            @Param("accessTokenExpiresAt") LocalDateTime accessTokenExpiresAt,
            @Param("tokenRefreshedAt") LocalDateTime tokenRefreshedAt,
            @Param("syncedAt") LocalDateTime syncedAt,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** A disconnect committed during an upstream call makes this update a no-op. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CreatorInstagramConnection connection SET "
            + "connection.status = :newStatus, connection.lastError = :lastError, "
            + "connection.encryptedAccessToken = :encryptedAccessToken, "
            + "connection.accessTokenExpiresAt = :accessTokenExpiresAt, "
            + "connection.tokenRefreshedAt = :tokenRefreshedAt, "
            + "connection.updatedAt = :updatedAt "
            + "WHERE connection.id = :id AND connection.status = :connected "
            + "AND (connection.encryptedAccessToken = :expectedEncryptedAccessToken "
            + "OR (connection.encryptedAccessToken IS NULL "
            + "AND :expectedEncryptedAccessToken IS NULL))")
    int markSyncFailedIfConnected(
            @Param("id") Integer id,
            @Param("connected") ConnectionStatus connected,
            @Param("newStatus") ConnectionStatus newStatus,
            @Param("expectedEncryptedAccessToken") String expectedEncryptedAccessToken,
            @Param("encryptedAccessToken") String encryptedAccessToken,
            @Param("accessTokenExpiresAt") LocalDateTime accessTokenExpiresAt,
            @Param("tokenRefreshedAt") LocalDateTime tokenRefreshedAt,
            @Param("lastError") String lastError,
            @Param("updatedAt") LocalDateTime updatedAt);

    long countByStatus(ConnectionStatus status);
}
