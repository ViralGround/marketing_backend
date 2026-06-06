package com.viralground.backend.repository;

import com.viralground.backend.entity.Campaign;
import com.viralground.backend.entity.CampaignStatus;
import com.viralground.backend.entity.EscrowStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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
            AND c.hiddenAt IS NULL
            AND (c.deadline IS NULL OR c.deadline > :now)
            """)
    List<Campaign> findOpenCampaignsAll(@Param("now") LocalDateTime now);

    @Query("""
            SELECT c FROM Campaign c
            WHERE c.status = com.viralground.backend.entity.CampaignStatus.OPEN
            AND c.hiddenAt IS NULL
            AND (c.deadline IS NULL OR c.deadline > :now)
            AND (LOWER(c.title) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(c.brandName) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    List<Campaign> findOpenCampaignsWithSearch(@Param("search") String search, @Param("now") LocalDateTime now);

    default List<Campaign> findOpenCampaigns(String search, LocalDateTime now) {
        if (search == null || search.isBlank()) {
            return findOpenCampaignsAll(now);
        }
        return findOpenCampaignsWithSearch(search.trim(), now);
    }

    @Query("""
            SELECT c FROM Campaign c
            WHERE (:status IS NULL OR c.status = :status)
            ORDER BY c.createdAt DESC
            """)
    List<Campaign> findAllByStatus(CampaignStatus status);

    /**
     * 랜딩 페이지 노출용 대표 캠페인. featuredOrder 가 지정된 OPEN 캠페인 중
     * 숨김·마감되지 않은 것만 지정 순번 오름차순으로 반환. 노출 건수 제한은 호출부에서.
     */
    @Query("""
            SELECT c FROM Campaign c
            WHERE c.featuredOrder IS NOT NULL
            AND c.status = com.viralground.backend.entity.CampaignStatus.OPEN
            AND c.hiddenAt IS NULL
            AND (c.deadline IS NULL OR c.deadline > :now)
            ORDER BY c.featuredOrder ASC, c.id ASC
            """)
    List<Campaign> findFeaturedOpen(@Param("now") LocalDateTime now);

    /**
     * 대표 캠페인 한도 검증용 — 실제 랜딩 노출 대상(OPEN·미숨김·미마감)과 동일 기준 카운트.
     * 숨김/마감된 featured 가 한도를 소모해 랜딩이 비어 보이는 모순을 막는다.
     */
    @Query("""
            SELECT COUNT(c) FROM Campaign c
            WHERE c.featuredOrder IS NOT NULL
            AND c.status = com.viralground.backend.entity.CampaignStatus.OPEN
            AND c.hiddenAt IS NULL
            AND (c.deadline IS NULL OR c.deadline > :now)
            """)
    long countFeaturedOpen(@Param("now") LocalDateTime now);

    /** featuredOrder 채번용 — 기존 최대 순번. 해제 후 재지정 시 순번 충돌/중복을 막는다. */
    @Query("SELECT COALESCE(MAX(c.featuredOrder), 0) FROM Campaign c")
    int maxFeaturedOrder();

    /** 회사 소개 모달용. 특정 기업(createdById)의 진행 중 OPEN 캠페인. */
    @Query("""
            SELECT c FROM Campaign c
            WHERE c.createdById = :memberId
            AND c.status = com.viralground.backend.entity.CampaignStatus.OPEN
            AND c.hiddenAt IS NULL
            AND (c.deadline IS NULL OR c.deadline > :now)
            ORDER BY c.createdAt DESC
            """)
    List<Campaign> findOpenByCreator(@Param("memberId") Integer memberId, @Param("now") LocalDateTime now);

    List<Campaign> findByCreatedByIdOrderByCreatedAtDesc(Integer createdById);

    List<Campaign> findByEscrowStatusOrderByDepositRequestedAtAsc(EscrowStatus escrowStatus);

    /**
     * 관리자 예치금 페이지용. PENDING_DEPOSIT 은 depositRequestedAt 이 null 이므로
     * 먼저 depositRequestedAt 순으로 정렬한 뒤 createdAt 으로 안정화한다.
     */
    List<Campaign> findByEscrowStatusInOrderByDepositRequestedAtAscCreatedAtAsc(
            Collection<EscrowStatus> escrowStatuses);
}
