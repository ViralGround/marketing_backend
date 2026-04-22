package com.viralground.backend.repository;

import com.viralground.backend.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Integer> {

    Optional<EmailVerificationCode> findByEmail(String email);

    void deleteByEmail(String email);
}
