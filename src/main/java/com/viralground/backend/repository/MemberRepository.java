package com.viralground.backend.repository;

import com.viralground.backend.entity.Member;
import com.viralground.backend.entity.MemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Integer> {

    Optional<Member> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByCreatedAtAfter(LocalDateTime createdAt);

    @Query("""
            SELECT m FROM Member m
            WHERE (:status IS NULL OR m.status = :status)
            AND (:search IS NULL OR LOWER(m.name) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(m.email) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY m.createdAt DESC
            """)
    List<Member> findAllByStatusAndSearch(MemberStatus status, String search);
}
