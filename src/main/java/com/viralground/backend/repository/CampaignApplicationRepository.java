package com.viralground.backend.repository;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface CampaignApplicationRepository extends JpaRepository<CampaignApplication, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM CampaignApplication a WHERE a.id = :id")
    Optional<CampaignApplication> findByIdForUpdate(Integer id);

    Optional<CampaignApplication> findByCampaignIdAndCreatorId(Integer campaignId, Integer creatorId);

    boolean existsByCampaignIdAndCreatorId(Integer campaignId, Integer creatorId);

    List<CampaignApplication> findByCreatorIdOrderByAppliedAtDesc(Integer creatorId);

    @Query("""
            SELECT a FROM CampaignApplication a
            WHERE a.creatorId = :creatorId
            AND (:status IS NULL OR a.status = :status)
            ORDER BY a.appliedAt DESC
            """)
    List<CampaignApplication> findByCreatorIdAndStatus(Integer creatorId, ApplicationStatus status);

    List<CampaignApplication> findByCampaignIdOrderByAppliedAtDesc(Integer campaignId);

    /** 릴스(submissionUrl)가 등록된 모든 지원 — 관리자 릴스 분석 대시보드용. */
    List<CampaignApplication> findBySubmissionUrlIsNotNull();

    /** Instagram batch가 한 번에 읽을 상한을 강제하는 조회. */
    List<CampaignApplication> findBySubmissionUrlIsNotNull(Pageable pageable);

    /** 연결 직후 해당 크리에이터의 제출물만 상한 내에서 읽는다. */
    List<CampaignApplication> findByCreatorIdAndSubmissionUrlIsNotNull(Integer creatorId, Pageable pageable);

    long countByCreatorIdAndStatus(Integer creatorId, ApplicationStatus status);

    long countByCreatorId(Integer creatorId);

    boolean existsByCreatorIdAndStatusIn(Integer creatorId, java.util.Collection<ApplicationStatus> statuses);

    long countByCampaignId(Integer campaignId);

    /** status 별 지원 수. KPI 대시보드의 매칭률·완료율 집계용. */
    @Query("SELECT a.status, COUNT(a) FROM CampaignApplication a GROUP BY a.status")
    List<Object[]> countByStatusGrouped();

    @Query("""
            SELECT a.campaignId AS campaignId, COUNT(a) AS count
            FROM CampaignApplication a
            WHERE a.campaignId IN :campaignIds
            GROUP BY a.campaignId
            """)
    List<CampaignCountRow> countByCampaignIdIn(java.util.List<Integer> campaignIds);

    interface CampaignCountRow {
        Integer getCampaignId();
        Long getCount();
    }

    @Query("SELECT COALESCE(SUM(a.rewardPaidAmount), 0) FROM CampaignApplication a WHERE a.creatorId = :creatorId AND a.status = 'SETTLED'")
    Long sumRewardByCreatorId(Integer creatorId);

    /** 크리에이터별 완료(SETTLED) 건수 — 공개 크리에이터 풀 목록용. 완료가 많은 순. */
    @Query("""
            SELECT a.creatorId AS creatorId, COUNT(a) AS completed
            FROM CampaignApplication a
            WHERE a.status = 'SETTLED'
            GROUP BY a.creatorId
            ORDER BY COUNT(a) DESC, MAX(a.settledAt) DESC
            """)
    List<CreatorCompletedRow> countSettledGroupedByCreator();

    interface CreatorCompletedRow {
        Integer getCreatorId();
        Long getCompleted();
    }

    /** 캠페인 하드 삭제 시 해당 캠페인의 지원을 일괄 제거. */
    void deleteByCampaignId(Integer campaignId);
}
