package com.viralground.backend.repository;

import com.viralground.backend.entity.SubmissionMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 한 크리에이터의 SETTLED 지원 건들에 대한 성과 집계.
     * returns [totalViews, totalLikes, totalComments, sampleSize] — sampleSize 는 metric 이 입력된 application 수.
     */
    @Query("""
            SELECT COALESCE(SUM(m.views), 0), COALESCE(SUM(m.likes), 0), COALESCE(SUM(m.comments), 0), COUNT(m)
            FROM SubmissionMetric m
            JOIN CampaignApplication a ON a.id = m.applicationId
            WHERE a.creatorId = :creatorId AND a.status = 'SETTLED'
            """)
    Object[] sumByCreatorId(@Param("creatorId") Integer creatorId);

    /**
     * 여러 크리에이터의 SETTLED 성과를 한 번에 집계 — 공개 크리에이터 풀 목록용.
     * returns rows of [creatorId, totalViews, sampleSize].
     */
    @Query("""
            SELECT a.creatorId, COALESCE(SUM(m.views), 0), COUNT(m)
            FROM SubmissionMetric m
            JOIN CampaignApplication a ON a.id = m.applicationId
            WHERE a.creatorId IN :creatorIds AND a.status = 'SETTLED'
            GROUP BY a.creatorId
            """)
    List<Object[]> sumSettledByCreatorIds(@Param("creatorIds") List<Integer> creatorIds);

    /** 캠페인 하드 삭제 시 지원들에 딸린 성과 기록을 일괄 제거. */
    void deleteByApplicationIdIn(List<Integer> applicationIds);
}
