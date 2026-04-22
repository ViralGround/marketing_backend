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

    long countByCampaignId(Integer campaignId);

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
