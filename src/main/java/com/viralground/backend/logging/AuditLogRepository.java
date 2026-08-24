package com.viralground.backend.logging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** 저장만 노출한다. 운영 DB trigger가 직접 SQL을 포함한 UPDATE/DELETE도 차단한다. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {
    <S extends AuditLog> S saveAndFlush(S entity);

    /**
     * Read-only operations view. Deliberately expose no update/delete method so the
     * application API mirrors the database append-only trigger.
     */
    @Query("""
            SELECT audit FROM AuditLog audit
            WHERE (:action IS NULL OR audit.action = :action)
              AND (:actorId IS NULL OR audit.actorId = :actorId)
              AND (:resourceType IS NULL OR audit.resourceType = :resourceType)
              AND (:resourceId IS NULL OR audit.resourceId = :resourceId)
              AND (:fromInclusive IS NULL OR audit.createdAt >= :fromInclusive)
              AND (:toExclusive IS NULL OR audit.createdAt < :toExclusive)
            """)
    Page<AuditLog> search(
            @Param("action") AuditAction action,
            @Param("actorId") Integer actorId,
            @Param("resourceType") String resourceType,
            @Param("resourceId") String resourceId,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable);
}
