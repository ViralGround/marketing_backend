package com.viralground.backend.repository;

import com.viralground.backend.entity.CreatorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreatorProfileRepository extends JpaRepository<CreatorProfile, Integer> {

    Optional<CreatorProfile> findByMemberId(Integer memberId);
}
