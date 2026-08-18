package com.viralground.backend.logging;

import org.springframework.data.repository.Repository;

/** 저장만 노출한다. 운영 DB trigger가 직접 SQL을 포함한 UPDATE/DELETE도 차단한다. */
public interface AuditLogRepository extends Repository<AuditLog, Long> {
    <S extends AuditLog> S saveAndFlush(S entity);
}
