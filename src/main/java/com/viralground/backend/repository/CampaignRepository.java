package com.viralground.backend.repository;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Integer> {

    /**
     * 에스크로 상태 전이(입금 확인·지급·환불)에 사용할 비관적 락 조회.
     * 같은 캠페인에 대한 동시 요청을 직렬화해 중복 지급/중복 DEPOSIT 기록을 방지.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Campaign c WHERE c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") Integer id);

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

    /**
     * 관리자 예치금 페이지용. PENDING_DEPOSIT 은 depositRequestedAt 이 null 이므로
     * 먼저 depositRequestedAt 순으로 정렬한 뒤 createdAt 으로 안정화한다.
     */
    List<Campaign> findByEscrowStatusInOrderByDepositRequestedAtAscCreatedAtAsc(
            Collection<EscrowStatus> escrowStatuses);
}
