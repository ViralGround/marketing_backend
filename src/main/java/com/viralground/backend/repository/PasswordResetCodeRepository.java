package com.viralground.backend.repository;

import com.viralground.backend.entity.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Integer> {
    Optional<PasswordResetCode> findByEmail(String email);

    void deleteByEmail(String email);
}
