package com.viralground.backend.repository;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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

    long countByCreatorIdAndStatus(Integer creatorId, ApplicationStatus status);

    long countByCreatorId(Integer creatorId);

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
}
