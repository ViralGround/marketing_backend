package com.viralground.backend.repository;

import com.viralground.backend.entity.CompanyProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Integer> {

    Optional<CompanyProfile> findByMemberId(Integer memberId);
}
