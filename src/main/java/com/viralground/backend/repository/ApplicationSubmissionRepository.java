package com.viralground.backend.repository;

import com.viralground.backend.entity.ApplicationSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationSubmissionRepository extends JpaRepository<ApplicationSubmission, Integer> {

    List<ApplicationSubmission> findByApplicationIdOrderBySubmittedAtAsc(Integer applicationId);

    Optional<ApplicationSubmission> findTopByApplicationIdOrderBySubmittedAtDesc(Integer applicationId);
}
