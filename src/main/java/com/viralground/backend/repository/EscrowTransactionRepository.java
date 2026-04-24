package com.viralground.backend.repository;

import com.viralground.backend.entity.EscrowTransaction;
import com.viralground.backend.entity.EscrowTxType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Integer> {

    List<EscrowTransaction> findByCampaignIdOrderByCreatedAtDesc(Integer campaignId);

    /** 입금 확인 멱등성 체크: 이미 DEPOSIT 트랜잭션이 기록됐는지. */
    boolean existsByCampaignIdAndType(Integer campaignId, EscrowTxType type);
}
