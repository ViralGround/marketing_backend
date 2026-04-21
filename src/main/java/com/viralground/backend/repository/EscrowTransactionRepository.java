package com.viralground.backend.repository;

import com.viralground.backend.entity.EscrowTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Integer> {

    List<EscrowTransaction> findByCampaignIdOrderByCreatedAtDesc(Integer campaignId);
}
