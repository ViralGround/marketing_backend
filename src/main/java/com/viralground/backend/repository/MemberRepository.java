package com.viralground.backend.repository;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import com.viralground.backend.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Integer> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByCreatedAtAfter(LocalDateTime createdAt);

    long countByRoleAndStatus(Role role, MemberStatus status);

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
