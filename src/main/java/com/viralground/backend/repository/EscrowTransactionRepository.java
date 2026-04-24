package com.viralground.backend.repository;

import com.viralground.backend.entity.EscrowTransaction;
import com.viralground.backend.entity.EscrowTxType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Integer> {

    List<EscrowTransaction> findByCampaignIdOrderByCreatedAtDesc(Integer campaignId);

    /** 입금 확인 멱등성 체크: 이미 DEPOSIT 트랜잭션이 기록됐는지. */
    boolean existsByCampaignIdAndType(Integer campaignId, EscrowTxType type);

    /** type 별 총합. KPI 대시보드의 GMV/지급/환불 집계용. 결과는 {type, sum} 배열. */
    @Query("SELECT t.type, COALESCE(SUM(t.amount), 0) FROM EscrowTransaction t GROUP BY t.type")
    List<Object[]> sumAmountByType();
}
