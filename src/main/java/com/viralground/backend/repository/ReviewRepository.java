package com.viralground.backend.repository;

import com.viralground.backend.entity.Review;
import com.viralground.backend.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Integer> {

    List<Review> findByApplicationIdOrderByCreatedAtAsc(Integer applicationId);

    Optional<Review> findByApplicationIdAndAuthorRole(Integer applicationId, Role authorRole);

    boolean existsByApplicationIdAndAuthorRole(Integer applicationId, Role authorRole);

    List<Review> findByTargetIdOrderByCreatedAtDesc(Integer targetId);

    /** 전체 평점 평균. 리뷰가 없으면 null 대신 0.0 반환. KPI 대시보드용. */
    @Query("SELECT COALESCE(AVG(r.rating), 0) FROM Review r")
    Double averageRating();

    /**
     * 여러 크리에이터가 받은 리뷰 평점을 한 번에 집계 — 공개 크리에이터 풀 목록용.
     * returns rows of [targetId, avgRating, count].
     */
    @Query("""
            SELECT r.targetId, AVG(r.rating), COUNT(r)
            FROM Review r
            WHERE r.targetId IN :targetIds
            GROUP BY r.targetId
            """)
    List<Object[]> ratingByTargetIds(List<Integer> targetIds);

    /** 캠페인 하드 삭제 시 지원들에 딸린 리뷰를 일괄 제거. */
    void deleteByApplicationIdIn(List<Integer> applicationIds);
}
