package com.viralground.backend.repository;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Integer> {

    Optional<Member> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT member FROM Member member WHERE member.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Integer id);

    boolean existsByEmail(String email);

    long countByCreatedAtAfter(LocalDateTime createdAt);

    long countByRoleAndStatus(Role role, MemberStatus status);

    /**
     * 전체·오늘 가입·이번 주 가입 카운트를 단일 쿼리로 집계.
     * 도쿄/오레곤처럼 DB 와 앱 서버가 떨어진 환경에서 별도 3쿼리 RTT 를 1쿼리로 줄이기 위함.
     */
    @Query("""
            SELECT
              COUNT(m) AS total,
              SUM(CASE WHEN m.createdAt >= :todayStart THEN 1 ELSE 0 END) AS todayCount,
              SUM(CASE WHEN m.createdAt >= :weekAgo THEN 1 ELSE 0 END) AS weekCount
            FROM Member m
            """)
    MemberStatsRow findMemberStats(@Param("todayStart") LocalDateTime todayStart,
                                   @Param("weekAgo") LocalDateTime weekAgo);

    interface MemberStatsRow {
        Long getTotal();
        Long getTodayCount();
        Long getWeekCount();
    }

    @Query("""
            SELECT m FROM Member m
            WHERE (:status IS NULL OR m.status = :status)
            ORDER BY m.createdAt DESC
            """)
    List<Member> findAllByStatusOrdered(@Param("status") MemberStatus status);

    @Query("""
            SELECT m FROM Member m
            WHERE (:status IS NULL OR m.status = :status)
            AND (LOWER(m.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(m.email) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY m.createdAt DESC
            """)
    List<Member> findAllByStatusWithSearch(@Param("status") MemberStatus status,
                                           @Param("search") String search);

    default List<Member> findAllByStatusAndSearch(MemberStatus status, String search) {
        if (search == null || search.isBlank()) {
            return findAllByStatusOrdered(status);
        }
        return findAllByStatusWithSearch(status, search.trim());
    }
}
