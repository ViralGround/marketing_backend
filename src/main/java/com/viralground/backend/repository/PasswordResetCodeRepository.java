package com.viralground.backend.repository;

import com.viralground.backend.entity.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Integer> {
    Optional<PasswordResetCode> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT code FROM PasswordResetCode code WHERE code.email = :email")
    Optional<PasswordResetCode> findByEmailForUpdate(@Param("email") String email);

    void deleteByEmail(String email);
}
