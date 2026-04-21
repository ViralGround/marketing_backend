package com.viralground.backend.repository;

import com.viralground.backend.entity.ApplicationStatus;
import com.viralground.backend.entity.CampaignApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CampaignApplicationRepository extends JpaRepository<CampaignApplication, Integer> {

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

    @Query("SELECT COALESCE(SUM(a.rewardPaidAmount), 0) FROM CampaignApplication a WHERE a.creatorId = :creatorId AND a.status = 'SETTLED'")
    Long sumRewardByCreatorId(Integer creatorId);
}
