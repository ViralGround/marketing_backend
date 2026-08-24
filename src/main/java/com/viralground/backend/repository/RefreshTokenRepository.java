package com.viralground.backend.repository;

import com.viralground.backend.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT token FROM RefreshToken token
            WHERE token.familyId = :familyId
            ORDER BY token.tokenId
            """)
    List<RefreshToken> findAllByFamilyIdForUpdate(@Param("familyId") String familyId);

    List<RefreshToken> findAllByFamilyId(String familyId);
    List<RefreshToken> findAllByMemberIdAndRevokedAtIsNull(Integer memberId);

    void deleteAllByMemberId(Integer memberId);
}
