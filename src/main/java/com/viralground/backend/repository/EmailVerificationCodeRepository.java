package com.viralground.backend.repository;

import com.viralground.backend.entity.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Integer> {

    Optional<EmailVerificationCode> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT code FROM EmailVerificationCode code WHERE code.email = :email")
    Optional<EmailVerificationCode> findByEmailForUpdate(@Param("email") String email);

    void deleteByEmail(String email);
}
