package com.viralground.backend.repository;

import com.viralground.backend.entity.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Integer> {

    Optional<EmailVerification> findByToken(String token);

    Optional<EmailVerification> findByMemberId(Integer memberId);

    void deleteByMemberId(Integer memberId);
}
