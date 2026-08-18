package com.viralground.backend.repository;

import com.viralground.backend.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    List<RefreshToken> findAllByFamilyId(String familyId);
    List<RefreshToken> findAllByMemberIdAndRevokedAtIsNull(Integer memberId);

    void deleteAllByMemberId(Integer memberId);
}
