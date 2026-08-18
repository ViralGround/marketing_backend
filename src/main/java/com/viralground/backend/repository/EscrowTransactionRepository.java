package com.viralground.backend.repository;

import com.viralground.backend.entity.EscrowTransaction;
import com.viralground.backend.entity.EscrowTxType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Integer> {

    List<EscrowTransaction> findByCampaignIdOrderByCreatedAtDesc(Integer campaignId);

    /** 입금 확인 멱등성 체크: 이미 DEPOSIT 트랜잭션이 기록됐는지. */
    boolean existsByCampaignIdAndType(Integer campaignId, EscrowTxType type);

    boolean existsByCampaignId(Integer campaignId);

    Optional<EscrowTransaction> findByIdempotencyKey(String idempotencyKey);

    Optional<EscrowTransaction> findFirstByCampaignIdAndApplicationIdAndType(
            Integer campaignId, Integer applicationId, EscrowTxType type);

    @Query("""
            SELECT t FROM EscrowTransaction t
            WHERE t.createdAt >= :fromInclusive AND t.createdAt < :toExclusive
            ORDER BY t.createdAt ASC, t.id ASC
            """)
    List<EscrowTransaction> findForReconciliation(
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM EscrowTransaction t WHERE t.campaignId = :campaignId AND t.type = :type")
    long sumAmountByCampaignIdAndType(
            @Param("campaignId") Integer campaignId, @Param("type") EscrowTxType type);

    /** type 별 총합. KPI 대시보드의 GMV/지급/환불 집계용. 결과는 {type, sum} 배열. */
    @Query("SELECT t.type, COALESCE(SUM(t.amount), 0) FROM EscrowTransaction t GROUP BY t.type")
    List<Object[]> sumAmountByType();

}
