package com.viralground.backend.repository;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CampaignRepository extends JpaRepository<Campaign, Integer> {

    @Query("""
            SELECT c FROM Campaign c
            WHERE c.status = 'OPEN'
            AND (:search IS NULL OR LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(c.brandName) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    List<Campaign> findOpenCampaigns(String search);

    @Query("""
            SELECT c FROM Campaign c
            WHERE (:status IS NULL OR c.status = :status)
            ORDER BY c.createdAt DESC
            """)
    List<Campaign> findAllByStatus(CampaignStatus status);
}
