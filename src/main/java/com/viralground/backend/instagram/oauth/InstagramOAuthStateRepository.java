package com.viralground.backend.instagram.oauth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface InstagramOAuthStateRepository extends JpaRepository<InstagramOAuthState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InstagramOAuthState s where s.stateHash = :stateHash")
    Optional<InstagramOAuthState> findByStateHashForUpdate(String stateHash);

    Optional<InstagramOAuthState> findTopByCreatorIdOrderByIdDesc(Integer creatorId);

    @Modifying
    @Query("update InstagramOAuthState s set s.usedAt = :now "
            + "where s.creatorId = :creatorId and s.usedAt is null")
    int invalidateUnusedByCreatorId(Integer creatorId, LocalDateTime now);

    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
