package com.viralground.backend.repository;

import com.viralground.backend.entity.SubmissionMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SubmissionMetricRepository extends JpaRepository<SubmissionMetric, Integer> {

    Optional<SubmissionMetric> findByApplicationId(Integer applicationId);

    List<SubmissionMetric> findByApplicationIdIn(List<Integer> applicationIds);

    @Query("""
            SELECT COALESCE(SUM(m.views), 0), COALESCE(SUM(m.likes), 0), COALESCE(SUM(m.comments), 0)
            FROM SubmissionMetric m
            """)
    Object[] sumAll();
}
