package com.viralground.backend.repository;

import com.viralground.backend.entity.Review;
import com.viralground.backend.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Integer> {

    List<Review> findByApplicationIdOrderByCreatedAtAsc(Integer applicationId);

    Optional<Review> findByApplicationIdAndAuthorRole(Integer applicationId, Role authorRole);

    boolean existsByApplicationIdAndAuthorRole(Integer applicationId, Role authorRole);

    List<Review> findByTargetIdOrderByCreatedAtDesc(Integer targetId);
}
