package com.viralground.backend.repository;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Integer> {

    @Query("""
            SELECT c FROM Campaign c
            WHERE c.status = com.viralground.backend.entity.CampaignStatus.OPEN
            """)
    List<Campaign> findOpenCampaignsAll();

    @Query("""
            SELECT c FROM Campaign c
            WHERE c.status = com.viralground.backend.entity.CampaignStatus.OPEN
            AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(c.brandName) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    List<Campaign> findOpenCampaignsWithSearch(@Param("search") String search);

    default List<Campaign> findOpenCampaigns(String search) {
        if (search == null || search.isBlank()) {
            return findOpenCampaignsAll();
        }
        return findOpenCampaignsWithSearch(search.trim());
    }

    @Query("""
            SELECT c FROM Campaign c
            WHERE (:status IS NULL OR c.status = :status)
            ORDER BY c.createdAt DESC
            """)
    List<Campaign> findAllByStatus(CampaignStatus status);

    List<Campaign> findByCreatedByIdOrderByCreatedAtDesc(Integer createdById);

    List<Campaign> findByEscrowStatusOrderByDepositRequestedAtAsc(EscrowStatus escrowStatus);
}
