package com.viralground.backend.repository;

import com.viralground.backend.entity.ApplicationSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApplicationSubmissionRepository extends JpaRepository<ApplicationSubmission, Integer> {

    List<ApplicationSubmission> findByApplicationIdOrderBySubmittedAtAsc(Integer applicationId);

    /** 캠페인 상세 화면의 제출 이력 N+1 방지용 벌크 조회. applicationId 기준 그룹화에 사용. */
    List<ApplicationSubmission> findByApplicationIdInOrderByApplicationIdAscSubmittedAtAsc(List<Integer> applicationIds);

    Optional<ApplicationSubmission> findTopByApplicationIdOrderBySubmittedAtDesc(Integer applicationId);
}
